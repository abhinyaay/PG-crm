/** Open a blob in a new tab (falls back to a download when pop-ups are blocked). */
export function openBlob(blob: Blob, filename: string, preOpened?: Window | null): void {
  const url = URL.createObjectURL(blob);
  if (preOpened && !preOpened.closed) {
    preOpened.location.href = url;
  } else {
    const a = document.createElement('a');
    a.href = url;
    a.download = filename;
    a.rel = 'noopener';
    document.body.appendChild(a);
    a.click();
    a.remove();
  }
  // give the new tab / download time to read the blob
  setTimeout(() => URL.revokeObjectURL(url), 60_000);
}

export function saveBlob(blob: Blob, filename: string): void {
  openBlob(blob, filename, null);
}
