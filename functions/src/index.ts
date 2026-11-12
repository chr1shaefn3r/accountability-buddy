import { initializeApp } from "firebase-admin/app";
import { getMessaging } from "firebase-admin/messaging";
import { onRequest } from "firebase-functions/v2/https";

import { AddressGone, relay as answer, type Push } from "./relay.js";

initializeApp();

/** How long the push service keeps a message for a phone that is offline. */
const TIME_TO_LIVE_MILLIS = 3 * 24 * 60 * 60 * 1000;

/** Error codes of the push service that mean the address will never work again. */
const GONE = new Set([
  "messaging/installation-id-not-registered",
  "messaging/registration-token-not-registered",
  "messaging/invalid-registration-token",
]);

const pushWithFirebase: Push = async (to, payload) => {
  try {
    await getMessaging().send({
      fid: to,
      data: { p: payload },
      android: { priority: "high", ttl: TIME_TO_LIVE_MILLIS },
    });
  } catch (error) {
    const code = (error as { code?: unknown }).code;
    if (typeof code === "string" && GONE.has(code)) throw new AddressGone();
    // Only the error code is logged: never the address or the payload.
    console.error("push failed", typeof code === "string" ? code : "unknown");
    throw error;
  }
};

/** Reachable as https://europe-west1-<project>.cloudfunctions.net/relay */
export const relay = onRequest(
  { region: "europe-west1", invoker: "public", maxInstances: 5, memory: "256MiB" },
  async (request, response) => {
    const status = await answer({ method: request.method, body: request.body }, pushWithFirebase);
    response.status(status).end();
  },
);
