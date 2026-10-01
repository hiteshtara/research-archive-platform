"""Each validation check rejects what it should (FICTIONAL rows only)."""
from validate_crosswalk import validate

P = [{"prncpl_id": "P1", "entity_id": "E1", "actv_ind": "Y", "_row": 2},
     {"prncpl_id": "P2", "entity_id": "E2", "actv_ind": "Y", "_row": 3},
     {"prncpl_id": "P3", "entity_id": "E3", "actv_ind": "N", "_row": 4},
     {"prncpl_id": "P4", "entity_id": "E1", "actv_ind": "Y", "_row": 5}]


def row(value, principal, n, name="attr"):
    return {"attribute_name": name, "attribute_value": value, "prncpl_id": principal,
            "evidence_ref": "T", "verified_by": "lab", "_row": n}


def checks(crosswalk, **kw):
    return set(validate(P, crosswalk, "attr", **kw)[0])


def test_clean_import_passes_and_inactive_is_skipped():
    problems, inactive = validate(P, [row("A1", "P1", 2), row("A2", "P2", 3), row("A3", "P3", 4)], "attr")
    assert problems == {} and inactive == [4]


def test_ambiguous_value_and_reused_principal_are_rejected():
    assert "attribute value maps to more than one principal" in checks([row("A1", "P1", 2), row("A1", "P2", 3)])
    assert "principal mapped from more than one attribute value" in checks([row("A1", "P1", 2), row("A2", "P1", 3)])


def test_email_values_are_rejected():
    assert "attribute value looks like an email address" in checks([row("pat@lab.invalid", "P1", 2)])


def test_unknown_and_rolodex_ids_are_rejected():
    assert "principal not in the KIM principal extract" in checks([row("A1", "NOPE", 2)])
    assert "non-employee (rolodex) id is never a login account" in checks([row("A1", "990001", 2)],
                                                                          rolodex_ids=frozenset({"990001"}))


def test_wrong_attribute_and_shared_entity_are_rejected():
    assert "attribute_name is not exactly the approved attribute" in checks([row("A1", "P1", 2, name="mail")])
    assert "several principals share one KIM entity" in checks([row("A1", "P1", 2), row("A4", "P4", 3)])
