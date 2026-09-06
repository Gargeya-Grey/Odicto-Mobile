export type SupportedAudio = { extension: string; mimeType: string };

export function detectAudio(buffer: Buffer): SupportedAudio | null {
  if (
    buffer.length >= 4 &&
    buffer.subarray(0, 4).equals(Buffer.from([0x1a, 0x45, 0xdf, 0xa3]))
  )
    return { extension: 'webm', mimeType: 'audio/webm' };
  if (
    buffer.length >= 12 &&
    buffer.toString('ascii', 0, 4) === 'RIFF' &&
    buffer.toString('ascii', 8, 12) === 'WAVE'
  )
    return { extension: 'wav', mimeType: 'audio/wav' };
  if (buffer.length >= 8 && buffer.toString('ascii', 4, 8) === 'ftyp')
    return { extension: 'm4a', mimeType: 'audio/mp4' };
  if (buffer.length >= 4 && buffer.toString('ascii', 0, 4) === 'OggS')
    return { extension: 'ogg', mimeType: 'audio/ogg' };
  if (buffer.length >= 4 && buffer.toString('ascii', 0, 4) === 'fLaC')
    return { extension: 'flac', mimeType: 'audio/flac' };
  if (
    buffer.length >= 3 &&
    (buffer.toString('ascii', 0, 3) === 'ID3' ||
      (buffer[0] === 0xff && (buffer[1] & 0xe0) === 0xe0))
  )
    return { extension: 'mp3', mimeType: 'audio/mpeg' };
  return null;
}
