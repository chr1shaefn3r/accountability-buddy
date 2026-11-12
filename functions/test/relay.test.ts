import assert from "node:assert/strict";
import { test } from "node:test";

import { AddressGone, MAX_PAYLOAD_LENGTH, relay, Status, type Push } from "../src/relay.js";

const address = "dQw4w9WgXcQ-installation";
const payload = Buffer.from("sealed message").toString("base64");

function recordingPush(): { push: Push; sent: { to: string; payload: string }[] } {
  const sent: { to: string; payload: string }[] = [];
  return {
    sent,
    push: (to, payload) => {
      sent.push({ to, payload });
      return Promise.resolve();
    },
  };
}

const failingPush =
  (error: Error): Push =>
  () =>
    Promise.reject(error);

void test("a valid message is forwarded unchanged", async () => {
  const { push, sent } = recordingPush();

  const status = await relay({ method: "POST", body: { to: address, payload } }, push);

  assert.equal(status, Status.delivered);
  assert.deepEqual(sent, [{ to: address, payload }]);
});

void test("only POST is accepted", async () => {
  const { push, sent } = recordingPush();

  for (const method of ["GET", "PUT", "DELETE", "OPTIONS"]) {
    assert.equal(await relay({ method, body: { to: address, payload } }, push), Status.methodNotAllowed);
  }
  assert.deepEqual(sent, []);
});

void test("a malformed request is rejected and nothing is forwarded", async () => {
  const { push, sent } = recordingPush();
  const malformed: unknown[] = [
    undefined,
    null,
    "text",
    [],
    {},
    { to: address },
    { payload },
    { to: "", payload },
    { to: 42, payload },
    { to: address, payload: 42 },
    { to: address, payload: "" },
    { to: address, payload: "not base64!" },
    { to: "x".repeat(257), payload },
  ];

  for (const body of malformed) {
    assert.equal(await relay({ method: "POST", body }, push), Status.badRequest, JSON.stringify(body));
  }
  assert.deepEqual(sent, []);
});

void test("an envelope of the app fits, anything larger is refused", async () => {
  const { push, sent } = recordingPush();
  const envelope = Buffer.alloc(2125).toString("base64");
  const tooLarge = "A".repeat(MAX_PAYLOAD_LENGTH + 4);

  assert.equal(await relay({ method: "POST", body: { to: address, payload: envelope } }, push), Status.delivered);
  assert.equal(
    await relay({ method: "POST", body: { to: address, payload: tooLarge } }, push),
    Status.payloadTooLarge,
  );
  assert.equal(sent.length, 1);
});

void test("an address the push service no longer knows is reported as gone", async () => {
  const status = await relay({ method: "POST", body: { to: address, payload } }, failingPush(new AddressGone()));

  assert.equal(status, Status.addressGone);
});

void test("any other push failure asks the phone to try again later", async () => {
  const status = await relay({ method: "POST", body: { to: address, payload } }, failingPush(new Error("unavailable")));

  assert.equal(status, Status.pushFailed);
});
