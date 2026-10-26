// Only server-issued structured references are actionable. Never parse citations from model prose.
export function normalizeDocumentReferences(value) {
  if (!Array.isArray(value)) return []
  return value.filter(item => item && typeof item === 'object'
    && ['referenceId', 'documentId', 'segmentId', 'generationId', 'contentHash', 'segmentHash'].every(key => typeof item[key] === 'string' && item[key].length > 0)
    && Number.isInteger(item.documentVersion) && item.documentVersion > 0
    && Number.isInteger(item.startOffset) && item.startOffset >= 0
    && Number.isInteger(item.endOffset) && item.endOffset >= item.startOffset)
    .map(item => Object.freeze(Object.fromEntries([
      'referenceId', 'documentId', 'documentVersion', 'documentTitle', 'segmentId',
      'generationId', 'contentHash', 'startOffset', 'endOffset', 'segmentHash',
    ].map(key => [key, key === 'documentTitle' ? (typeof item[key] === 'string' ? item[key] : '') : item[key]]))))
}
