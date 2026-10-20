//! reagen-core — ядро визуального редактора процедур (аналог MCreator procedures).
//!
//! Ответственности:
//!  * модели графа процедуры (Elements/Inputs/Branches) с единым camelCase-контрактом
//!    UI(TS) <-> Rust <-> Java (см. java-backend/reagen/parser/ProcedureGraphParser);
//!  * мост к resident JVM-процессу верификатора (`net.reagen.verify.VerifyCli --daemon`):
//!    запрос/ответ JSON, разделённые маркером `%%EOT%%`.
//!
//! Java-подложка делает ТОЛЬКО проверку присланного из редактора кода/графа —
//! интерпретацией и хранением проектов занимается эта сторона.

use serde::{Deserialize, Serialize};
use std::io::{BufRead, BufReader, Write};
use std::process::{Child, Command, Stdio};

pub const EOT: &str = "%%EOT%%";

/// Тип переменной процедуры (зеркало net.reagen.ast.McType).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum McType {
    Number,
    String,
    Logic,
    #[serde(rename = "itemstack")]
    ItemStack,
    Entity,
    World,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Field {
    pub name: String,
    pub value: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Input {
    pub name: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub shadow_type: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub connected_element_id: Option<String>,
}

/// Blockly-подобный блок графа. `branches` — списки id стартовых блоков веток (if/loop).
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Element {
    pub id: String,
    #[serde(rename = "type")]
    pub el_type: String,
    #[serde(default)]
    pub x: i32,
    #[serde(default)]
    pub y: i32,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub output_next: Option<String>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub fields: Vec<Field>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub inputs: Vec<Input>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub branches: Vec<Vec<String>>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct NamedVar {
    pub name: String,
    #[serde(rename = "type")]
    pub var_type: McType,
}

/// Полная процедура — то, что редактор отправляет на проверку в Java.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Procedure {
    pub name: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub mod_id: Option<String>,
    #[serde(rename = "returnType")]
    pub return_type: McType,
    pub root_id: String,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub args: Vec<NamedVar>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub locals: Vec<NamedVar>,
    pub elements: Vec<Element>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Issue {
    pub element_id: String,
    pub severity: String, // ERROR | WARNING
    pub rule: String,
    pub message: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct VerifyReport {
    pub valid: bool,
    #[serde(default)]
    pub issues: Vec<Issue>,
}

impl VerifyReport {
    /// Ошибки по id блока — для подсветки узлов графа в UI.
    pub fn errors_by_element(&self) -> std::collections::HashMap<String, Vec<&Issue>> {
        let mut map: std::collections::HashMap<String, Vec<&Issue>> = Default::default();
        for i in &self.issues {
            map.entry(i.element_id.clone()).or_default().push(i);
        }
        map
    }
}

/// Порт блока из реестра Java (net.reagen.blocks.BlockDef) — описание входа.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct BlockPort {
    pub name: String,
    #[serde(rename = "type")]
    pub port_type: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub shadow: Option<String>,
}

/// Описание блока палитры, полученное из Java-реестра (`VerifyCli --blocks`).
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct BlockDef {
    #[serde(rename = "type")]
    pub block_type: String,
    /// "statement" | "expression"
    pub kind: String,
    pub category: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub output: Option<String>,
    #[serde(default)]
    pub fields: Vec<String>,
    #[serde(default)]
    pub inputs: Vec<BlockPort>,
    #[serde(default)]
    pub branches: u8,
    #[serde(default)]
    pub needs_player: bool,
}

/// Resident-мост к JVM-верификатору. Один процесс Java переиспользуется между
/// проверками (старт JVM ~300 мс — единичная цена, дальше <10 мс на прогон).
pub struct JavaVerifier {
    child: Child,
    stdin: std::process::ChildStdin,
    stdout: BufReader<std::process::ChildStdout>,
}

impl JavaVerifier {
    /// jar_path — собранный reagen.jar (scripts/build.sh), class иначе.
    pub fn spawn(jar_path: &str) -> std::io::Result<Self> {
        let mut child = Command::new("java")
            .args(["-cp", jar_path, "net.reagen.verify.VerifyCli", "--daemon"])
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::inherit())
            .spawn()?;
        let stdin = child.stdin.take().expect("stdin");
        let stdout = BufReader::new(child.stdout.take().expect("stdout"));
        Ok(Self { child, stdin, stdout })
    }

    /// Один кадр запрос/ответ протокола daemon-канала.
    fn request(&mut self, payload: &str) -> std::io::Result<String> {
        writeln!(self.stdin, "{payload}")?;
        writeln!(self.stdin, "{EOT}")?;
        self.stdin.flush()?;
        let mut resp = String::new();
        loop {
            let mut line = String::new();
            if self.stdout.read_line(&mut line)? == 0 {
                return Err(std::io::Error::new(std::io::ErrorKind::UnexpectedEof, "JVM closed"));
            }
            if line.trim() == EOT {
                break;
            }
            resp.push_str(&line);
        }
        Ok(resp)
    }

    /// Проверить процедуру: сериализовать граф -> Java (4 уровня: структура, типы,
    /// javac против стабов Forge API, headless-симуляция «игрового теста») -> отчёт.
    pub fn verify(&mut self, procedure: &Procedure) -> std::io::Result<VerifyReport> {
        let req = serde_json::to_string(procedure).map_err(std::io::Error::other)?;
        let resp = self.request(&req)?;
        serde_json::from_str(&resp).map_err(|e| std::io::Error::other(format!("bad report: {e}: {resp}")))
    }

    /// Сгенерировать Java-код процедуры без проверки (для панели «Код» редактора).
    pub fn generate_java(&mut self, procedure: &Procedure) -> std::io::Result<String> {
        let req = serde_json::to_string(procedure).map_err(std::io::Error::other)?;
        let resp = self.request(&req)?;
        let v: serde_json::Value = serde_json::from_str(&resp)
            .map_err(|e| std::io::Error::other(format!("bad generate reply: {e}: {resp}")))?;
        v.get("java").and_then(|x| x.as_str()).map(str::to_owned).ok_or_else(|| {
            std::io::Error::other(format!("no java field in: {resp}"))
        })
    }
}

/// Выгрузить реестр блоков из Java-стороны (палитра редактора = единый источник истины).
/// Отдельный короткий JVM-вызов: `java -cp jar net.reagen.verify.VerifyCli --blocks`.
pub fn fetch_block_registry(jar_path: &str) -> std::io::Result<Vec<BlockDef>> {
    let out = Command::new("java")
        .args(["-cp", jar_path, "net.reagen.verify.VerifyCli", "--blocks"])
        .output()?;
    if !out.status.success() {
        return Err(std::io::Error::other(format!(
            "blocks registry failed: {}", String::from_utf8_lossy(&out.stderr))));
    }
    let text = String::from_utf8_lossy(&out.stdout);
    serde_json::from_str(text.trim()).map_err(|e| std::io::Error::other(format!("bad blocks json: {e}")))
}

impl Drop for JavaVerifier {
    fn drop(&mut self) {
        let _ = self.child.kill();
        let _ = self.child.wait();
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sample() -> Procedure {
        Procedure {
            name: "giveDiamond".into(),
            mod_id: None,
            return_type: McType::Logic,
            root_id: "e1".into(),
            args: vec![],
            locals: vec![NamedVar { name: "cnt".into(), var_type: McType::Number }],
            elements: vec![
                Element {
                    id: "e1".into(),
                    el_type: "procedures_setvar".into(),
                    x: 0, y: 0,
                    output_next: Some("e2".into()),
                    fields: vec![Field { name: "VAR".into(), value: "cnt".into() }],
                    inputs: vec![Input {
                        name: "VALUE".into(),
                        shadow_type: Some("math_number".into()),
                        connected_element_id: Some("n1".into()),
                    }],
                    branches: vec![],
                },
                Element {
                    id: "n1".into(),
                    el_type: "math_number".into(),
                    x: 0, y: 0,
                    output_next: None,
                    fields: vec![Field { name: "NUM".into(), value: "64".into() }],
                    inputs: vec![],
                    branches: vec![],
                },
                Element {
                    id: "e2".into(),
                    el_type: "return_logic".into(),
                    x: 0, y: 0,
                    output_next: None,
                    fields: vec![],
                    inputs: vec![Input {
                        name: "RETURN".into(),
                        shadow_type: Some("logic_boolean".into()),
                        connected_element_id: None,
                    }],
                    branches: vec![],
                },
            ],
        }
    }

    #[test]
    fn contract_is_camel_case_and_java_readable() {
        let json = serde_json::to_string(&sample()).unwrap();
        // Ключи, которые ждёт ProcedureGraphParser (Java):
        assert!(json.contains("\"rootId\":\"e1\""));
        assert!(json.contains("\"returnType\":\"logic\""));
        assert!(json.contains("\"connectedElementId\":\"n1\""));
        assert!(json.contains("\"outputNext\":\"e2\""));
        assert!(json.contains("\"shadowType\":\"math_number\""));
        // round-trip через наши же модели
        let back: Procedure = serde_json::from_str(&json).unwrap();
        assert_eq!(back.elements.len(), 3);
    }

    #[test]
    fn blockdef_models_parse_registry_json() {
        // ровно тот формат, который отдаёт BlockDef.toJson() на Java-стороне
        let raw = r#"[{"type":"mc_play_sound","kind":"statement","category":"game",
            "fields":["CATEGORY"],
            "inputs":[{"name":"SOUND","type":"string","shadow":"text"}],
            "needsPlayer":false},
            {"type":"itemstack_make","kind":"expression","category":"game","output":"itemstack",
            "inputs":[{"name":"ITEM","type":"string","shadow":"text"},
                      {"name":"COUNT","type":"number","shadow":"math_number"}]}]"#;
        let defs: Vec<BlockDef> = serde_json::from_str(raw).unwrap();
        assert_eq!(defs.len(), 2);
        assert_eq!(defs[0].block_type, "mc_play_sound");
        assert_eq!(defs[0].inputs[0].port_type, "string");
        assert_eq!(defs[0].inputs[0].shadow.as_deref(), Some("text"));
        assert_eq!(defs[1].output.as_deref(), Some("itemstack"));
        assert_eq!(defs[1].inputs[1].name, "COUNT");
    }

    #[test]
    fn report_deserializes_with_element_mapping() {
        let raw = r#"{"valid":false,"issues":[
            {"elementId":"e1","severity":"ERROR","rule":"SIM_DIV_BY_ZERO","message":"x"},
            {"elementId":"sim","severity":"ERROR","rule":"SIM_UNKNOWN_ENTITY","message":"y"}]}"#;
        let rep: VerifyReport = serde_json::from_str(raw).unwrap();
        assert!(!rep.valid);
        let by = rep.errors_by_element();
        assert_eq!(by["e1"][0].rule, "SIM_DIV_BY_ZERO");
        assert!(by.contains_key("sim"));
    }
}
