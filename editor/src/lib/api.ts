// UI-слой редактора: кнопка «Проверить» вызывает Tauri-команду verify_procedure,
// ошибки маппятся на шаги графа (stepIndex/line из отчёта Java-верификатора).

export interface Param { name: string; type: string }

export type Step =
  | { kind: 'sql';    id: string; line?: number; sql: string }
  | { kind: 'guard';  id: string; line?: number; existsQuery: string; message: string }
  | { kind: 'return'; id: string; line?: number; selectSql: string }
  | { kind: 'assign'; id: string; line?: number; variable: string; expression: string };

export interface ProcedureSpec {
  name: string;
  dialect: 'postgresql' | 'mysql';
  params: Param[];
  steps: Step[];
}

export interface Issue {
  stepIndex: number; line: number;
  severity: 'ERROR' | 'WARNING' | 'INFO';
  rule: string; message: string;
}

export interface Report { valid: boolean; errors: Issue[]; generatedSql?: string }

/** Вызов Rust-моста (Tauri invoke). В браузере без Tauri — mock для превью UI. */
export async function verifyProcedure(spec: ProcedureSpec): Promise<Report> {
  const w = window as any;
  if (w.__TAURI__?.invoke) {
    return await w.__TAURI__.invoke('verify_procedure', { spec });
  }
  // eslint-disable-next-line no-console
  console.warn('Tauri runtime not available — returning empty report');
  return { valid: false, errors: [{ stepIndex: -1, line: 0, severity: 'INFO', rule: 'MOCK', message: 'preview mode' }] };
}

export async function generateCode(spec: ProcedureSpec): Promise<string> {
  const w = window as any;
  if (w.__TAURI__?.invoke) return await w.__TAURI__.invoke('generate_code', { spec });
  return '-- preview mode: генерация доступна только в desktop-сборке';
}
