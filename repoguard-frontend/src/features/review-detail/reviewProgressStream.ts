import { openReviewEventStream } from "@/api/client";

const MAX_FRAME_CHARACTERS = 16_384;
const DECODE_SLICE_BYTES = 4096;

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
      // Decode bounded slices so a coalesced network chunk cannot grow the frame buffer without limit.
      let offset = 0;
      while (offset < result.value.length && !controller.signal.aborted) {
        const size = Math.min(DECODE_SLICE_BYTES, MAX_FRAME_CHARACTERS + 1 - buffer.length);
        const end = Math.min(result.value.length, offset + size);
        buffer += decoder.decode(result.value.subarray(offset, end), { stream: true });
        offset = end;
        // Keep a trailing CR until the next slice so split CRLF is not treated as two newlines.
        let boundary: RegExpExecArray | null;
        while (!controller.signal.aborted && (boundary = /\r?\n\r?\n/.exec(buffer))) {
          const frameEnd = boundary.index + boundary[0].length;
          if (frameEnd > MAX_FRAME_CHARACTERS) throw new Error("Progress stream frame exceeded budget");
          const frame = buffer.slice(0, boundary.index);
          buffer = buffer.slice(frameEnd);
          const lines = frame.split(/\r?\n/);
          const event = lines.find(line => line.startsWith("event:"))?.slice(6).trim();
          const id = lines.find(line => line.startsWith("id:"))?.slice(3).trim() ?? "";
          if (event === "progress" && /^\d{1,19}$/.test(id)) {
            await onProgress(id);
          }
        }
        if (buffer.length > MAX_FRAME_CHARACTERS) throw new Error("Progress stream frame exceeded budget");
      }
    }
  } finally {
    clearTimeout(idleTimer!);
    await reader?.cancel().catch(() => undefined);
    reader?.releaseLock();
  }
};
