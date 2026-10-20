//! Tauri-команды редактора: verify_procedure / generate_code.
//! state — resident JavaVerifier (подход C из ADR-001).

mod java_bridge {
    // Единая реализация моста — в crate sqlgen-core (тестируется без Tauri).
    pub use sqlgen_core::*;
}

use java_bridge::{JavaVerifier, ProcedureSpec, Report};
use std::sync::{Arc, Mutex, OnceLock};

static VERIFIER: OnceLock<Arc<Mutex<Option<JavaVerifier>>>> = OnceLock::new();

fn verifier_slot() -> &'static Arc<Mutex<Option<JavaVerifier>>> {
    VERIFIER.get_or_init(|| Arc::new(Mutex::new(None)))
}

fn with_verifier<R>(f: impl FnOnce(&JavaVerifier) -> Result<R, String>) -> Result<R, String> {
    let slot = verifier_slot();
    let mut guard = slot.lock().unwrap();
    if guard.is_none() {
        // classpath подложки резолвится относительно бандла Tauri (resource_dir)
        let cp = std::env::var("SQLGEN_CLASSPATH")
            .unwrap_or_else(|_| "../java-backend/sqlgen/out".into());
        let ddl = std::env::var("SQLGEN_SCHEMA_DDL").ok();
        *guard = Some(JavaVerifier::spawn(&cp, ddl.as_deref()).map_err(|e| e.to_string())?);
    }
    f(guard.as_ref().unwrap())
}

#[cfg(not(test))]
#[tauri::command]
async fn verify_procedure(spec: ProcedureSpec) -> Result<Report, String> {
    // blocking: JVM IPC не должен блокировать UI-поток
    tauri::async_runtime::spawn_blocking(move || {
        with_verifier(|v| v.verify(&spec).map_err(|e| e.to_string()))
    })
    .await
    .map_err(|e| e.to_string())?
}

#[cfg(not(test))]
#[tauri::command]
fn generate_code(spec: ProcedureSpec) -> Result<String, String> {
    // генерация — часть того же отчёта: проверяем и забираем generatedSql
    let report = with_verifier(|v| v.verify(&spec).map_err(|e| e.to_string()))?;
    if !report.valid {
        return Err(format!("процедура не прошла проверку: {} ошибок", report.errors.len()));
    }
    Ok(report.generated_sql.unwrap_or_default())
}

#[cfg(not(test))]
fn main() {
    tauri::Builder::default()
        .invoke_handler(tauri::generate_handler![verify_procedure, generate_code])
        .run(tauri::generate_context!())
        .expect("error while running SQLGen Studio");
}

// Юнит-тесты без Tauri: сериализация спеки совпадает с форматом Java-парсера.
#[cfg(test)]
mod tests {
    use super::java_bridge::*;

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
        assert!(json.contains("\"exists_query\""));
    }
}
