export const SURVEY_QUESTIONS = [
  "最近一次让你费时或容易出错的工作是什么？",
  "当时怎么做，需要和谁配合或交接？",
  "这类工作多久做一次，每次大约花多久？",
  "这给你或其他人带来了什么影响？",
  "你最希望哪一步变得轻松一些？",
];

export function composeSurvey(freeText: string, answers: string[], recordings: { guideContext?: { question: string } }[] = []) {
  const actualAnswers = [freeText, ...answers].map(text => text.trim()).filter(Boolean);
  const recordingContexts = recordings.map((recording, index) => recording.guideContext?.question.trim() ? `录音${index + 1}对应提问：${recording.guideContext.question.trim()}` : "");
  const problem = [freeText.trim(), ...answers.map((answer, index) => answer.trim() ? `问：${SURVEY_QUESTIONS[index]}\n答：${answer.trim()}` : ""), ...recordingContexts].filter(Boolean).join("\n\n");
  const firstAnswer = actualAnswers[0] ?? "";
  const firstSentence = firstAnswer.split(/[。！？!?\n]/u).find(text => text.trim())?.trim() ?? firstAnswer;
  return { problem, title: firstSentence ? firstSentence.slice(0, 100) : recordings.length ? "语音反馈" : "", answerLength: actualAnswers.join("").length, answeredCount: answers.filter(answer => answer.trim()).length };
}
