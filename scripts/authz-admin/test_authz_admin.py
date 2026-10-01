"""Argument and import-plan rules of authz_admin.py (no database; FICTIONAL values only)."""
import pytest

import authz_admin as admin


def grant(**overrides):
    args = dict(grant_type="UNIT", grantee="SYN-INST-1", granted_by="SYN-ADMIN-1", approved_by="SYN-ADMIN-2",
                reason="SYNTHETIC test", unit="SYN-U-100", io=None, include_descendants=False, expires_at=None)
    args.update(overrides)
    return admin.check_grant_args(**args)


def refused(**overrides):
    with pytest.raises(admin.AdminError) as exc:
        grant(**overrides)
    return str(exc.value)


def test_a_well_formed_unit_grant_is_normalised():
    g = grant(unit=" SYN-U-100 ", include_descendants=True)
    assert g["grant_type"] == "UNIT" and g["unit_number"] == "SYN-U-100" and g["include_descendants"] is True
    assert g["io_value"] is None


def test_self_grant_and_self_approval_are_refused():
    assert "self-grant" in refused(granted_by="SYN-INST-1")
    assert "approve their own" in refused(approved_by="SYN-INST-1")


def test_central_needs_a_second_person():
    assert "second person" in refused(grant_type="CENTRAL", unit=None, approved_by="SYN-ADMIN-1")
    g = grant(grant_type="CENTRAL", unit=None)
    assert g["unit_number"] is None and g["io_value"] is None


def test_every_grant_needs_approver_and_reason():
    assert "--approved-by" in refused(approved_by=" ")
    assert "--reason" in refused(reason="")
    assert "--granted-by" in refused(granted_by=None)


def test_grant_shapes_match_the_schema():
    assert "--unit" in refused(unit=None)
    assert "--unit" in refused(io="SYN-IO-1")
    assert "IO grant" in refused(grant_type="IO", unit=None, io=None)
    assert "IO grant" in refused(grant_type="IO", unit=None, io="SYN-IO-1", include_descendants=True)
    assert "CENTRAL grant takes no" in refused(grant_type="CENTRAL", unit="SYN-U-100")
    assert "one of" in refused(grant_type="CONTACT_DERIVATION", unit=None)
    assert grant(grant_type="IO", unit=None, io="SYN-IO-1")["io_value"] == "SYN-IO-1"


def test_expiry_must_be_an_offset_timestamp():
    assert "UTC offset" in refused(expires_at="2027-01-01T00:00:00")
    assert "ISO-8601" in refused(expires_at="next year")
    assert grant(expires_at="2027-01-01T00:00:00+00:00")["expires_at"].year == 2027


P = [{"prncpl_id": "P1", "entity_id": "E1", "actv_ind": "Y", "_row": 2},
     {"prncpl_id": "P2", "entity_id": "E2", "actv_ind": "Y", "_row": 3},
     {"prncpl_id": "P3", "entity_id": "E3", "actv_ind": "N", "_row": 4}]


def row(value, principal, n):
    return {"attribute_name": "attr", "attribute_value": value, "prncpl_id": principal,
            "evidence_ref": "T", "verified_by": "lab", "_row": n}


def test_plan_skips_inactive_principals_and_refuses_any_validation_failure():
    problems, plan = admin.plan_import(P, [row("A1", "P1", 2), row("A3", "P3", 3)], "attr")
    assert problems == {} and plan.skipped_inactive == 1
    assert [r[:3] for r in plan.rows] == [("attr", "A1", "P1")]
    assert len(plan.principals) == 3          # inactive principals are still loaded, as inactive

    problems, plan = admin.plan_import(P, [row("pat@example.invalid", "P1", 2)], "attr")
    assert plan is None and problems


def test_existing_active_mappings_are_never_silently_replaced():
    rows = [("attr", "A1", "P1", "T", "x"), ("attr", "A2", "P2", "T", "x"), ("attr", "A9", "P9", "T", "x")]
    by_value = {("attr", "A1"): "P1", ("attr", "A2"): "P-OTHER"}
    by_principal = {("attr", "P1"): "A1", ("attr", "P-OTHER"): "A2"}
    to_insert, unchanged, conflicts = admin.classify_rows(rows, by_value, by_principal)
    assert unchanged == 1 and conflicts == 1 and [r[1] for r in to_insert] == ["A9"]
    # The same principal under a new value is also a conflict.
    _, _, conflicts = admin.classify_rows([("attr", "A-NEW", "P1", "T", "x")], by_value, by_principal)
    assert conflicts == 1


def test_the_dsn_comes_only_from_the_environment(monkeypatch, capsys):
    monkeypatch.delenv(admin.ENV_DSN, raising=False)
    assert admin.main(["list"]) == 2
    assert admin.ENV_DSN in capsys.readouterr().err
    with pytest.raises(SystemExit):
        admin.build_parser().parse_args(["list", "--dsn", "postgresql://x"])


def test_a_refused_grant_never_connects(monkeypatch, capsys):
    monkeypatch.setenv(admin.ENV_DSN, "postgresql://SECRET-SHOULD-NOT-PRINT@127.0.0.1:1/x")
    monkeypatch.setattr(admin, "connect", lambda: pytest.fail("connected for a refused grant"))
    code = admin.main(["grant-add", "--type", "CENTRAL", "--grantee", "G", "--granted-by", "A",
                       "--approved-by", "A", "--reason", "r"])
    assert code == 2
    err = capsys.readouterr().err
    assert "second person" in err and "SECRET" not in err


def test_a_connection_failure_never_prints_the_dsn(monkeypatch, capsys):
    pytest.importorskip("psycopg")
    monkeypatch.setenv(admin.ENV_DSN, "postgresql://user:SECRET-PW@127.0.0.1:1/none?connect_timeout=1")
    assert admin.main(["list"]) == 2
    err = capsys.readouterr().err
    assert "SECRET" not in err and "127.0.0.1" not in err
