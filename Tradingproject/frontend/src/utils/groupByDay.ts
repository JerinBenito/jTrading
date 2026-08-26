const IST_TZ = 'Asia/Kolkata';

function istDateKey(iso: string) {
  return new Date(iso).toLocaleDateString('en-CA', { timeZone: IST_TZ }); // YYYY-MM-DD
}

function dayLabel(dateKey: string) {
  const todayKey = new Date().toLocaleDateString('en-CA', { timeZone: IST_TZ });
  const yesterdayKey = new Date(Date.now() - 86400000).toLocaleDateString('en-CA', {
    timeZone: IST_TZ,
  });
  if (dateKey === todayKey) return 'Today';
  if (dateKey === yesterdayKey) return 'Yesterday';
  return new Date(`${dateKey}T00:00:00`).toLocaleDateString('en-IN', {
    day: '2-digit',
    month: 'short',
    year: 'numeric',
  });
}

/** Groups items (newest-first input, e.g. prediction history) into day sections for a SectionList, using the IST calendar day of `getTs`. */
export function groupByDay<T>(items: T[], getTs: (item: T) => string) {
  const sections: { title: string; data: T[] }[] = [];
  let currentKey: string | null = null;

  for (const item of items) {
    const key = istDateKey(getTs(item));
    if (key !== currentKey) {
      sections.push({ title: dayLabel(key), data: [] });
      currentKey = key;
    }
    sections[sections.length - 1].data.push(item);
  }
  return sections;
}
