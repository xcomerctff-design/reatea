//! Мост Rust -> Java верификатор (подход C из docs/ADR-procedure-verification.md).
//!
//! Долгоживущий daemon-процесс `java -cp sqlgen.jar org.sqlgen.verify.VerifyMain --daemon`
//! принимает JSON-спецификации в stdin и отдаёт отчёты в stdout, разделённые EOT (\u{4}).
//! Переиспользование процесса устраняет JVM-старт на каждой проверке.

use serde::{Deserialize, Serialize};
use std::io::{BufRead, BufReader, Write};
use std::process::{Child, ChildStdin, ChildStdout, Command, Stdio};
use std::sync::Mutex;
use thiserror::Error;

pub const EOT: u8 = 0x04;

#[derive(Debug, Error)]
pub enum VerifyError {
    #[error("не удалось запустить JVM-верификатор: {0}")]
    Spawn(std::io::Error),
    #[error("ошибка записи запроса: {0}")]
    Io(#[from] std::io::Error),
    #[error("верификатор молчит (обрыв канала)")]
    BrokenPipe,
}

#[derive(Serialize, Deserialize, Debug, Clone)]
pub struct Param { pub name: String, pub r#type: String }

#[derive(Serialize, Deserialize, Debug, Clone)]
// camelCase — контракт с UI (api.ts); Java-парсер принимает оба регистра.
#[serde(tag = "kind", rename_all = "lowercase", rename_all_fields = "camelCase")]
pub enum Step {
    Sql    { id: String, #[serde(default)] line: u32, sql: String },
    Guard  { id: String, #[serde(default)] line: u32, exists_query: String, message: String },
    Return { id: String, #[serde(default)] line: u32, select_sql: String },
    Assign { id: String, #[serde(default)] line: u32, variable: String, expression: String },
}

#[derive(Serialize, Deserialize, Debug, Clone)]
pub struct ProcedureSpec {
    pub name: String,
    #[serde(default)]
    pub dialect: String,
    #[serde(default)]
    pub params: Vec<Param>,
    #[serde(default)]
    pub steps: Vec<Step>,
}

#[derive(Deserialize, Debug)]
pub struct Issue {
    #[serde(rename = "stepIndex")] pub step_index: i32,
    pub line: u32,
    pub severity: String,
    pub rule: String,
    pub message: String,
}

#[derive(Deserialize, Debug)]
pub struct Report {
    pub valid: bool,
    pub errors: Vec<Issue>,
    #[serde(rename = "generatedSql", default)]
    pub generated_sql: Option<String>,
}

/// Обёртка над resident JVM-процессом.
pub struct JavaVerifier {
    child: Mutex<Child>,
    stdin: Mutex<ChildStdin>,
    stdout: Mutex<BufReader<ChildStdout>>,
}

impl JavaVerifier {
    /// classpath — путь к собранной подложке (out/ или sqlgen.jar), schema_ddl — опционально.
    pub fn spawn(classpath: &str, schema_ddl: Option<&str>) -> Result<Self, VerifyError> {
        let mut cmd = Command::new("java");
        cmd.arg("-cp").arg(classpath)
           .arg("org.sqlgen.verify.VerifyMain")
           .arg("--daemon");
        if let Some(s) = schema_ddl {
            cmd.arg("--schema").arg(s);
        }
        let mut child = cmd
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::inherit())
            .spawn()
            .map_err(VerifyError::Spawn)?;
        let stdin = child.stdin.take().ok_or(VerifyError::Spawn(std::io::Error::other("no stdin")))?;
        let stdout = child.stdout.take().ok_or(VerifyError::Spawn(std::io::Error::other("no stdout")))?;
        Ok(Self { child: Mutex::new(child), stdin: Mutex::new(stdin), stdout: Mutex::new(BufReader::new(stdout)) })
    }

    /// Синхронная проверка одной спецификации. Вызывается из Tauri-команды в blocking-потоке.
    pub fn verify(&self, spec: &ProcedureSpec) -> Result<Report, VerifyError> {
        let req = serde_json::to_string(spec).unwrap_or_default();
        {
            let mut si = self.stdin.lock().unwrap();
            si.write_all(req.as_bytes())?;
            si.write_all(b"\n")?;
            si.flush()?;
        }
        // читаем до EOT
        let mut buf = Vec::new();
        {
            let mut so = self.stdout.lock().unwrap();
            loop {
                let b = so.fill_buf()?;
                if b.is_empty() { return Err(VerifyError::BrokenPipe); }
                if let Some(i) = b.iter().position(|&x| x == EOT) {
                    buf.extend_from_slice(&b[..i]);
                    so.consume(i + 1);
                    break;
                } else {
                    buf.extend_from_slice(b);
                    let n = b.len();
                    so.consume(n);
                }
            }
        }
        let json = String::from_utf8_lossy(&buf).trim().to_string();
        serde_json::from_str::<Report>(&json)
            .map_err(|e| VerifyError::Io(std::io::Error::new(std::io::ErrorKind::InvalidData, e)))
    }
}

impl Drop for JavaVerifier {
    fn drop(&mut self) {
        let _ = self.child.lock().unwrap().kill();
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn spec_serializes_to_java_format() {
        let spec = ProcedureSpec {
            name: "get_orders".into(),
            dialect: "postgresql".into(),
            params: vec![Param { name: "p_id".into(), r#type: "BIGINT".into() }],
            steps: vec![Step::Guard {
                id: "g1".into(), line: 2,
                exists_query: "SELECT 1 FROM customers c WHERE c.id = p_id".into(),
                message: "not found".into(),
            }],
        };
        let json = serde_json::to_string(&spec).unwrap();
        assert!(json.contains("\"kind\":\"guard\""));
        // контракт с UI и Java-парсером: camelCase
        assert!(json.contains("\"existsQuery\""), "expected camelCase existsQuery in {json}");
        assert!(json.contains("\"dialect\":\"postgresql\""));
        assert!(json.contains("\"message\""));
    }

    #[test]
    fn report_deserializes_from_java_output() {
        let java_out = r#"{"valid":false,"errors":[{"stepIndex":0,"line":1,"severity":"ERROR","rule":"SCHEMA_TABLE","message":"Неизвестная таблица"}]}"#;
        let r: Report = serde_json::from_str(java_out).unwrap();
        assert!(!r.valid);
        assert_eq!(r.errors[0].rule, "SCHEMA_TABLE");
    }
}
