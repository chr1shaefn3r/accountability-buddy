/**
 * The relay passes a sealed message on to another phone. It knows nothing about the
 * message: the payload is an opaque blob that only the receiving phone can open.
 * It stores nothing and logs nothing about what it forwards.
 */

/** Largest accepted payload, in characters of base64. */
export const MAX_PAYLOAD_LENGTH = 3072;

/** Firebase installation IDs are short; anything much longer is not an address. */
const MAX_ADDRESS_LENGTH = 256;

const BASE64 = /^[A-Za-z0-9+/]+={0,2}$/;

export interface RelayRequest {
  method: string;
  body: unknown;
}

/** Hands a payload to the push service. Rejects if the push service does. */
export type Push = (to: string, payload: string) => Promise<void>;

/** The push service no longer knows the address. Retrying is pointless. */
export class AddressGone extends Error {}

export const Status = {
  delivered: 204,
  badRequest: 400,
  methodNotAllowed: 405,
  addressGone: 410,
  payloadTooLarge: 413,
  pushFailed: 502,
} as const;

export type StatusCode = (typeof Status)[keyof typeof Status];

/** Decides what to answer to a request, forwarding the payload if the request is valid. */
export async function relay(request: RelayRequest, push: Push): Promise<StatusCode> {
  if (request.method !== "POST") return Status.methodNotAllowed;

  const message = parse(request.body);
  if (message === undefined) return Status.badRequest;
  if (message.payload.length > MAX_PAYLOAD_LENGTH) return Status.payloadTooLarge;

  try {
    await push(message.to, message.payload);
    return Status.delivered;
  } catch (error) {
    return error instanceof AddressGone ? Status.addressGone : Status.pushFailed;
  }
}

function parse(body: unknown): { to: string; payload: string } | undefined {
  if (typeof body !== "object" || body === null) return undefined;
  const { to, payload } = body as Record<string, unknown>;
  if (typeof to !== "string" || to.length === 0 || to.length > MAX_ADDRESS_LENGTH) return undefined;
  if (typeof payload !== "string" || !BASE64.test(payload)) return undefined;
  return { to, payload };
}
