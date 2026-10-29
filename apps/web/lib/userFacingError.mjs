const GENERIC_ERROR = "The check couldn't be completed. Please try again.";
const INTERNAL_DETAIL = /(?:traceback|stack trace|exception|internal server|database|postgres|supabase|sqlstate|uvicorn|fastapi|proxy|[A-Z]:\\|\/(?:app|home|tmp)\/)/i;

export function userFacingRequestMessage(status, detail = "") {
  if (status === 413) return "The upload is too large. Please choose a smaller file.";
  if (status === 429) return "Too many requests. Please wait a moment and try again.";
  if (status === 503) return "The service is busy right now. Please try again shortly.";
  const safeDetail = typeof detail === "string" ? detail.trim() : "";
  if (status === 422 && safeDetail.length > 0 && safeDetail.length <= 200 && !INTERNAL_DETAIL.test(safeDetail) && !/[\r\n]/.test(safeDetail)) {
    return safeDetail;
  }
  return GENERIC_ERROR;
}
