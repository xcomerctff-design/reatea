// Компонент панели результатов проверки (Svelte-подобная псевдо-заготовка).
// Отображает Report из verifyProcedure: бейджи по шагам + список ошибок с переходом к строке.

import { verifyProcedure, generateCode } from '../lib/api';
import type { ProcedureSpec, Report } from '../lib/api';

export class VerifyPanel {
  report: Report | null = null;
  busy = false;

  async runCheck(spec: ProcedureSpec) {
    this.busy = true;
    try {
      this.report = await verifyProcedure(spec);   // Tauri invoke -> Java daemon
    } finally {
      this.busy = false;
    }
  }

  /** Подсветка в редакторе: stepIndex -> id узла графа, line -> позиция внутри шага. */
  issuesForStep(stepIndex: number) {
    return (this.report?.errors ?? []).filter(e => e.stepIndex === stepIndex);
  }

  async exportSql(spec: ProcedureSpec): Promise<string> {
    return generateCode(spec); // вернёт ошибку, если проверка не пройдена
  }
}
