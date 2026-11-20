let draft = ''
export function setQuestionDraft(value: string) { draft = value }
export function takeQuestionDraft() { const value = draft; draft = ''; return value }
