"""Optional post-sign-in hook, the simulator's stand-in for a Cognito trigger.

LAB_POST_SIGN_IN_HOOK=module:function names a function on PYTHONPATH that
receives (issuer, provider, profile) and returns a JSON-serialisable outcome.
The core lab knows nothing about any application; an application (such as
the archive, identity-lab/archive/enrollment) supplies its own hook.
"""
import importlib
import os

_SPEC = os.environ.get("LAB_POST_SIGN_IN_HOOK", "")


def post_sign_in(issuer, provider, profile):
    if not _SPEC:
        return {"hook": "none"}
    module, function = _SPEC.split(":")
    try:
        return getattr(importlib.import_module(module), function)(issuer, provider, profile)
    except Exception as e:  # a failing hook never blocks sign-in; the app fails closed itself
        return {"hook": _SPEC, "error": type(e).__name__}
