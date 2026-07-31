# BU Oracle Staging Connectivity

How the Award Attachment loader (`etl/load_award_attachments.py --ecs`) reaches
BU's staging Oracle (KCOEUS) RDS instance from inside this project's ECS
loader task, why each piece of networking is required, and which resources
this project owns versus BU's central IT.

## Why "same AWS account" did not provide connectivity

The Research Archive Platform's loader VPC (`vpc-0590614d7cfcdedf6`,
`10.30.0.0/16`) and BU's Oracle staging VPC (`vpc-0290de1785982a52f`,
`10.58.32.0/22`) are both in AWS account `770203350335` - but an AWS account
is a billing/IAM boundary, not a network boundary. Two VPCs in the same
account are exactly as isolated from each other as two VPCs in different
accounts: by default, neither can route traffic to the other at all, no
matter what IAM permissions exist. Network reachability between VPCs always
requires an explicit connection (peering, Transit Gateway, PrivateLink,
VPN) - the account boundary is irrelevant to it.

## Why VPC peering alone was insufficient

A `aws_vpc_peering_connection` (`pcx-0de8eec5f514370ca`) only creates the
*possibility* of routing traffic between the two VPCs - it does not, by
itself, route any traffic or open any port. Three more things were required
on top of it:

1. **Routes in the loader's own route table** telling it that traffic to
   the Oracle subnets should go over the peering connection, not out to the
   internet or nowhere.
2. **Routes in the Oracle side's route tables** telling *it* that traffic
   back to `10.30.0.0/16` should go over the same peering connection.
3. **A security-group rule on the Oracle RDS security group** allowing the
   loader's security group in on TCP 1521 - peering does not imply any
   security-group trust, it only makes the network path exist.

Without all three, packets have nowhere to go (no route) or are rejected on
arrival (no security-group ingress) even though the peering connection
itself shows `Status: active`.

## Why both forward and return routes were required

VPC peering routes, like all AWS routes, are **directional and
non-transitive** - each side's route table only affects traffic *originating*
from resources in that VPC. The loader's route table entry (destination
`10.58.33.0/25` and `10.58.33.128/25`, target the peering connection) only
tells the loader's own subnet how to send a request *out*. It says nothing
about how the Oracle side sends its *response* back. Without a matching
route in the Oracle-side route tables (destination `10.30.0.0/16`, target
the same peering connection), the Oracle RDS instance's response packets
would have no route back to the loader and the connection would hang or
reset - this is what "asymmetric routing" looks like at the TCP level: the
SYN arrives, but the SYN-ACK has nowhere to go.

## Why Oracle security-group ingress was required

Even with both directions of routing in place, AWS security groups default
to deny-all inbound. The Oracle RDS security group (`sg-019199c2d80636821`)
already has ~15 unrelated ingress rules for other BU systems (SnapLogic,
Informatica, SAP BW, on-campus/VPN CIDR ranges, Huron, Data Engineering) -
none of them reference this project's security group. A rule referencing
the loader's security group (`sg-0817befcc5b4affc9`) directly, rather than
a CIDR range, was required to allow this specific ECS task's traffic on
TCP 1521 - and was preferred over any CIDR-based rule per this project's
own constraint (a security-group reference is exact and self-maintaining as
the loader's own IP/ENI changes across task restarts, unlike a CIDR that
would need to track the loader subnet and could over-admit other resources
in it).

## Why the staging Oracle endpoint was used

This project's `dev` Terraform environment is, by construction, non-production
(no MFA-hardened access controls, `deletion_protection` mostly off, etc.), and
the loader work at this stage is still active development and validation, not
a production cutover. Pointing it at BU's staging Oracle instance
(`stg.db.kualitest.research.bu.edu`, database `kuali`) rather than a
production KC instance keeps this development/validation work isolated from
production research-administration data while still exercising the exact
same connectivity path (peering, routing, security groups, Secrets Manager,
`oracledb`/`DPY-`-family error surface) production would use.

## How to troubleshoot DPY-6005

`DPY-6005` (`python-oracledb`: "cannot connect to database") is a *network-level*
connection failure, not an authentication or SQL error - it means the driver
never even completed a TCP handshake with the listener. In order of
likelihood, check:

1. **Route tables** - does the loader's private route table
   (`rtb-0ac901d94d6ab2972`) have a route for both Oracle subnet CIDRs
   (`10.58.33.0/25`, `10.58.33.128/25`) via the peering connection? Does
   *each* Oracle-side route table (`rtb-03aee2071636aad9e`,
   `rtb-04ca6e1a522dc2b08`) have the return route for `10.30.0.0/16`?
   ```bash
   aws ec2 describe-route-tables --route-table-ids rtb-0ac901d94d6ab2972 \
     --query 'RouteTables[0].Routes' --region us-east-1
   ```
2. **Peering connection status** - is it still `active`? (BU's side could
   terminate/replace it independently of this project.)
   ```bash
   aws ec2 describe-vpc-peering-connections \
     --vpc-peering-connection-ids pcx-0de8eec5f514370ca --region us-east-1 \
     --query 'VpcPeeringConnections[0].Status'
   ```
3. **Security group** - does `sg-019199c2d80636821` still have the TCP 1521
   ingress rule referencing `sg-0817befcc5b4affc9`? (See "How to verify... "
   below for the exact command.)
4. **DNS resolution** - see the next section; a DPY-6005 can also mean the
   hostname resolved to the *wrong* IP (e.g. a stale record, or resolution
   falling through to a public endpoint that isn't reachable at all from
   inside the VPC).
5. **The loader task's own security group egress** - confirmed unrestricted
   (`0.0.0.0/0`, all protocols) for `sg-0817befcc5b4affc9`; this is not
   expected to be the cause, but confirm it hasn't been narrowed.
6. **Whether the task is actually running in the private subnets** with the
   loader security group attached - a misconfigured `run-task
   --network-configuration` (wrong subnet/security-group override) would
   produce the exact same symptom without any of the above actually being
   broken.

## How to verify DNS and routes

**DNS**: The peering connection has DNS resolution across it disabled on
both sides (`AllowDnsResolutionFromRemoteVpc: false`) - this is intentional
and does not need to be enabled. The loader VPC has no private hosted zone
for `research.bu.edu`; `stg.db.kualitest.research.bu.edu` resolves via
ordinary public DNS to a private (RFC 1918) address
(`10.58.33.43`) - a common "split-horizon"-style pattern where a publicly
resolvable name returns an internal address. From inside the running ECS
task (e.g. via `aws ecs execute-command`, if enabled, or a one-off
`--dry-run` invocation that logs the resolved host):

```bash
python3 -c "import socket; print(socket.gethostbyname('stg.db.kualitest.research.bu.edu'))"
# expect: 10.58.33.43
```

If this returns anything other than `10.58.33.43`, the connectivity work in
this document is irrelevant to whatever it actually returned - fix DNS
first.

**Routes** (from a machine with the `bu-nprd` AWS profile, not from inside
the task):

```bash
# Loader side - expect routes to both Oracle subnet CIDRs via the pcx:
aws ec2 describe-route-tables --route-table-ids rtb-0ac901d94d6ab2972 \
  --region us-east-1 --query 'RouteTables[0].Routes'

# Oracle side (repeat for both route tables) - expect a route to
# 10.30.0.0/16 via the same pcx:
aws ec2 describe-route-tables --route-table-ids rtb-03aee2071636aad9e \
  --region us-east-1 --query 'RouteTables[0].Routes'
aws ec2 describe-route-tables --route-table-ids rtb-04ca6e1a522dc2b08 \
  --region us-east-1 --query 'RouteTables[0].Routes'

# Security group - expect an ingress rule for tcp/1521 referencing
# sg-0817befcc5b4affc9:
aws ec2 describe-security-group-rules --region us-east-1 \
  --filters "Name=group-id,Values=sg-019199c2d80636821" \
  --query "SecurityGroupRules[?ReferencedGroupInfo.GroupId=='sg-0817befcc5b4affc9']"
```

## Ownership: this project versus shared BU infrastructure

| Resource | Owner | How it's represented in this repo |
| --- | --- | --- |
| Loader VPC, private route table, loader security group | This project | Fully managed (`terraform/modules/vpc`, `terraform/modules/ecs`) |
| VPC peering connection (`pcx-0de8eec5f514370ca`) | This project (same-account, requester side) | Managed resource (`aws_vpc_peering_connection`), adopted via `terraform import` |
| Loader-side routes to the Oracle subnets | This project | Managed resource (`aws_route`), in this project's own route table |
| Oracle staging VPC (`vpc-0290de1785982a52f`) | BU central IT | **Data source only** (`data "aws_vpc"`) - never a managed resource; this project has no business creating, modifying, or destroying BU's VPC |
| Oracle-side route tables (`rtb-03aee2071636aad9e`, `rtb-04ca6e1a522dc2b08`) | BU central IT | **Not managed as tables** - each carries many unrelated BU routes (Transit Gateway routes to `128.197.0.0/16`, `155.41.0.0/16`, `168.122.0.0/16`, `172.16.0.0/16`, `10.0.0.0/8`, NAT gateways, other VPC endpoints). Only the single `10.30.0.0/16` return route this project depends on is a managed `aws_route` resource, adopted via `terraform import` |
| Oracle RDS security group (`sg-019199c2d80636821`) | BU central IT | **Not managed as a security group** - it carries ~15 unrelated ingress rules from other BU systems and teams (SnapLogic, Informatica, SAP BW, Huron, on-campus/VPN CIDRs, Data Engineering). Only the single TCP 1521 ingress rule referencing this project's loader security group is a managed `aws_vpc_security_group_ingress_rule` resource, adopted via `terraform import` |
| Oracle RDS instance itself | BU central IT | Not represented in Terraform at all - referenced only by its DNS endpoint (`stg.db.kualitest.research.bu.edu:1521/kuali`), configured via the `ORACLE_SECRET_ID` Secrets Manager secret's `dsn` field, never created/modified/destroyed by this project |

The guiding rule applied throughout: **adopt (import) the exact resource
or rule this project depends on, never the shared resource it lives
inside of.** A route table or security group with unrelated entries from
other teams is data this project reads context from, not something it
takes ownership of.
