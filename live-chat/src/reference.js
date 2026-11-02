// Keep the whole original character inside Odyssey's portrait frame.
export async function prepareReference(blob) {
  const bitmap = await createImageBitmap(blob);
  try {
    const canvas = document.createElement('canvas');
    canvas.width = 704; canvas.height = 1280;
    const context = canvas.getContext('2d');
    context.fillStyle = '#eae6f2';
    context.fillRect(0, 0, canvas.width, canvas.height);
    const scale = Math.min(canvas.width / bitmap.width, canvas.height / bitmap.height);
    const width = bitmap.width * scale, height = bitmap.height * scale;
    context.drawImage(bitmap, (canvas.width - width) / 2, (canvas.height - height) / 2, width, height);
    return await new Promise((resolve, reject) => canvas.toBlob(result => result ? resolve(result) : reject(new Error('Image preparation failed')), 'image/png'));
  } finally { bitmap.close(); }
}
