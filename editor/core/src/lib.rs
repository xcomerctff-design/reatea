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

    /// Проверить процедуру: сериализовать граф -> Java (4 уровня: структура, типы,
    /// javac против стабов Forge API, headless-симуляция «игрового теста») -> отчёт.
    pub fn verify(&mut self, procedure: &Procedure) -> std::io::Result<VerifyReport> {
        let req = serde_json::to_string(procedure).map_err(std::io::Error::other)?;
        writeln!(self.stdin, "{req}")?;
        writeln!(self.stdin, "{EOT}")?;
        self.stdin.flush()?;
        let mut resp = String::new();
        self.stdout.read_line(&mut resp)?;
        // читаем до маркера конца фрейма
        while !resp.trim_end().ends_with('}') {
            let mut line = String::new();
            if self.stdout.read_line(&mut line)? == 0 {
                return Err(std::io::Error::new(std::io::ErrorKind::UnexpectedEof, "JVM closed"));
            }
            if line.trim() == EOT {
                break;
            }
            resp.push_str(&line);
        }
        serde_json::from_str(&resp).map_err(|e| std::io::Error::other(format!("bad report: {e}: {resp}")))
    }
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
