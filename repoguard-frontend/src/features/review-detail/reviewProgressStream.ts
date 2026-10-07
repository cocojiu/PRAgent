import { openReviewEventStream } from "@/api/client";

/** Parse bounded SSE frames, including split UTF-8/chunks and CRLF, without placing tokens in URLs. */
export const readReviewProgressStream = async (
  taskId: number,
  controller: AbortController,
  cursor: string,
  onProgress: (eventId: string) => Promise<void>
) => {
  let idleTimer: ReturnType<typeof setTimeout>;
  const touch = () => {
    clearTimeout(idleTimer);
    idleTimer = setTimeout(() => controller.abort(), 20_000);
  };
  touch();
  let reader: ReadableStreamDefaultReader<Uint8Array> | undefined;
  try {
    const response = await openReviewEventStream(taskId, controller.signal, cursor);
    if (!response.ok || !response.headers.get("Content-Type")?.startsWith("text/event-stream") || !response.body) {
      await response.body?.cancel();
      throw new Error("Progress stream unavailable");
    }
    reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = "";
    while (!controller.signal.aborted) {
      const result = await reader.read();
      if (result.done) break;
      touch();
      buffer += decoder.decode(result.value, { stream: true });
      if (buffer.length > 16_384) throw new Error("Progress stream frame exceeded budget");
      // Keep a trailing CR until the next chunk so split CRLF is not interpreted as two newlines.
      let boundary: RegExpExecArray | null;
      while ((boundary = /\r?\n\r?\n/.exec(buffer))) {
        const frame = buffer.slice(0, boundary.index);
        buffer = buffer.slice(boundary.index + boundary[0].length);
        const lines = frame.split(/\r?\n/);
        const event = lines.find(line => line.startsWith("event:"))?.slice(6).trim();
        const id = lines.find(line => line.startsWith("id:"))?.slice(3).trim() ?? "";
        if (event === "progress" && /^\d{1,19}$/.test(id)) {
          await onProgress(id);
        }
      }
    }
  } finally {
    clearTimeout(idleTimer!);
    await reader?.cancel().catch(() => undefined);
    reader?.releaseLock();
  }
};
