// Aster owns provider I/O and credentials in its authenticated Python bridge.
// Pi's compaction always receives our StreamFn; prevent accidental direct I/O.
export async function completeSimple() {
  throw new Error('Aster requires the authenticated model transport');
}
