// Presentation helpers for the Subaward Contacts tab - business
// identity (name/role/organization/contact info) instead of raw
// internal identifiers. Kept framework-free so it can be unit-tested
// with plain node:test, matching this repo's existing features/*/*.mjs
// convention.
//
// Proven live via Java/OJB/Oracle (SubAwardContact.java,
// repository-subAward.xml, KCOEUS.CONTACT_TYPE): contactTypeDescription
// resolves CONTACT_TYPE_CODE through the shared CONTACT_TYPE lookup
// (the same table Award's own contacts use); fullName/organization/
// email/phone resolve through EITHER archive.rolodex OR archive.person
// (SubAwardContact.setRolodex()/setKcPerson() are mutually exclusive -
// a contact is never linked to both). archive.person is deliberately
// scoped to person_ids already referenced elsewhere in this archive,
// so a real Subaward contact can still resolve to no name at all - the
// business question ("who do I call?") then has no answer archived,
// and the UI must say so honestly rather than inventing one.

// "Who do I call?" for one contact row:
//   name          - fullName, or null if unresolved
//   role          - contactTypeDescription, falling back to the raw
//                   code only when no description was ever archived
//                   for that code (never blank)
//   organization/phone/email - archived contact detail, or null
//   hasIdentity   - true when name resolved to something real -
//                   drives whether the UI leads with the name or with
//                   an honest "Contact not archived" state
export function resolveContactDisplay(contact) {
  const role = contact.contactTypeDescription ?? contact.contactTypeCode;

  return {
    name: contact.fullName,
    role,
    organization: contact.organization,
    phone: contact.phone,
    email: contact.email,
    hasIdentity: Boolean(contact.fullName),
  };
}
