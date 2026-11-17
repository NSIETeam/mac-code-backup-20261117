export interface ReviewLesson {
  sequence: number
  handoutId?: string
  state?: string
}

// Continue in lecture order, wrapping only to unfinished earlier lectures.
// A missing handout is unfinished work, not permission to skip the lecture.
export function nextReviewLesson<T extends ReviewLesson>(lessons: T[], currentId: string, currentSequence: number): T | undefined {
  const ordered = lessons.slice().sort((a, b) => a.sequence - b.sequence)
  const index = ordered.findIndex(item => item.handoutId === currentId)
  const candidates = index >= 0
    ? ordered.slice(index + 1).concat(ordered.slice(0, index))
    : ordered.filter(item => item.sequence > currentSequence).concat(ordered.filter(item => item.sequence <= currentSequence))
  return candidates.find(item => item.handoutId !== currentId && item.state !== 'published')
}
