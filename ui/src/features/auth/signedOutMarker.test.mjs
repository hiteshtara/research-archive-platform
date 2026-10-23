import assert from "node:assert/strict";
import test from "node:test";

import {
  SIGNED_OUT_MARKER,
  clearSignedOutMarker,
  markSignedOut,
  wasSignedOut,
} from "./signedOutMarker.mjs";

function fakeStorage(initial = {}) {
  const data = { ...initial };
  return {
    getItem: (k) => (k in data ? data[k] : null),
    setItem: (k, v) => {
      data[k] = String(v);
    },
    removeItem: (k) => {
      delete data[k];
    },
    _data: data,
  };
}

function throwingStorage() {
  return {
    getItem: () => {
      throw new Error("storage disabled");
    },
    setItem: () => {
      throw new Error("storage disabled");
    },
    removeItem: () => {
      throw new Error("storage disabled");
    },
  };
}

test("a fresh session is not marked signed out", () => {
  assert.equal(wasSignedOut(fakeStorage()), false);
});

test("signing out sets the marker, and it reads back", () => {
  const storage = fakeStorage();

  markSignedOut(storage);

  assert.equal(wasSignedOut(storage), true);
  assert.equal(storage._data[SIGNED_OUT_MARKER], "1");
});

test("signing in again clears the marker", () => {
  const storage = fakeStorage();

  markSignedOut(storage);
  clearSignedOutMarker(storage);

  assert.equal(wasSignedOut(storage), false);
});

test("an unrelated value is not treated as signed out", () => {
  // Only the exact marker counts, so a stale or hand-edited value cannot
  // strand a signed-in user on the signed-out page.
  for (const value of ["0", "", "true", "yes"]) {
    const storage = fakeStorage({ [SIGNED_OUT_MARKER]: value });
    assert.equal(wasSignedOut(storage), false, value);
  }
});

test("storage that throws never breaks sign-out", () => {
  const storage = throwingStorage();

  assert.doesNotThrow(() => markSignedOut(storage));
  assert.doesNotThrow(() => clearSignedOutMarker(storage));
});

test("storage that throws fails open rather than claiming signed out", () => {
  // Failing closed here would strand a user who never signed out on a
  // signed-out page with no way into the application.
  assert.equal(wasSignedOut(throwingStorage()), false);
});
