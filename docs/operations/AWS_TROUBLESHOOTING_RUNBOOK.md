# Research Archive Platform: AWS and Bash Troubleshooting Handbook

This handbook is for the development architecture shown in the network diagram. It favors read-only discovery and current AWS data over hard-coded assumptions.

## Known diagram values

| Item | Value shown |
|---|---|
| Region | `us-east-1` |
| Research Archive AWS account | `770203350335` |
| Research Archive VPC | `vpc-0590614d7cfcdedf6` |
| Research Archive CIDR | `10.30.0.0/16` |
| Public subnets | `10.30.1.0/24`, `10.30.2.0/24` |
| Private app subnets | `10.30.10.0/24`, `10.30.11.0/24` |
| Private data subnets | `10.30.20.0/24`, `10.30.21.0/24` |
| RDS endpoint | `research-archive-platform-dev-postgres.clb9d4mkglfd.us-east-1.rds.amazonaws.com` |
| RDS private IP shown | `10.30.12.216` |
| PostgreSQL port | `5432` |
| RDS security group | `sg-0ded3637b04384f60` |
| VPC peering | Discover from AWS; historical notes contain both `pcx-0cce7300e7a34f6b1` and later `pcx-0de8eec5f514370ca` |
| Oracle/Kuali VPC | `vpc-0290de1785982a52f` |
| Oracle/Kuali CIDR | `10.58.32.0/22` |
| Peered Oracle ranges | `10.58.33.0/25`, `10.58.33.128/25` |
| Transit gateway | `tgw-04783e1fe787c0074` |
| Transit gateway owner account | `422067408210` |
| BU on-campus range | `168.122.84.240/28` |
| BU off-campus/VPN range | `168.122.78.128/28` |

Treat diagram values as historical until AWS confirms them.

## 1. Safe shell setup

Run this once in each terminal. Use the BU non-production profile expected to access account `770203350335`.

```bash
export AWS_PROFILE=bu-nprd
export AWS_REGION=us-east-1
export AWS_DEFAULT_REGION=us-east-1

export RAP_ACCOUNT_ID=770203350335
export RAP_VPC_ID=vpc-0590614d7cfcdedf6
export RAP_RDS_SG_ID=sg-0ded3637b04384f60
# Resolve this below. Do not trust a historical peering ID.
export RAP_PEERING_ID=
export RAP_TGW_ID=tgw-04783e1fe787c0074
export RAP_ORACLE_VPC_ID=vpc-0290de1785982a52f
export RAP_RDS_HOST=research-archive-platform-dev-postgres.clb9d4mkglfd.us-east-1.rds.amazonaws.com
export RAP_RDS_PORT=5432
```

Resolve the active peering connection from the two VPC IDs:

```bash
export RAP_PEERING_ID="$(aws ec2 describe-vpc-peering-connections \
  --filters \
    Name=status-code,Values=active \
    Name=requester-vpc-info.vpc-id,Values="$RAP_VPC_ID" \
    Name=accepter-vpc-info.vpc-id,Values="$RAP_ORACLE_VPC_ID" \
  --query 'VpcPeeringConnections[0].VpcPeeringConnectionId' \
  --output text)"

if test -z "$RAP_PEERING_ID" || test "$RAP_PEERING_ID" = None; then
  export RAP_PEERING_ID="$(aws ec2 describe-vpc-peering-connections \
    --filters \
      Name=status-code,Values=active \
      Name=requester-vpc-info.vpc-id,Values="$RAP_ORACLE_VPC_ID" \
      Name=accepter-vpc-info.vpc-id,Values="$RAP_VPC_ID" \
    --query 'VpcPeeringConnections[0].VpcPeeringConnectionId' \
    --output text)"
fi

echo "RAP_PEERING_ID=$RAP_PEERING_ID"
```

Confirm tools and credentials:

```bash
command -v aws
aws --version
command -v jq
command -v dig
command -v nc
command -v psql

aws configure list
aws configure list-profiles
aws sts get-caller-identity
aws sts get-caller-identity --query '{Account:Account,Arn:Arn,UserId:UserId}' --output table
```

Hard stop if this prints anything other than `770203350335`:

```bash
if test "$(aws sts get-caller-identity --query Account --output text)" = "$RAP_ACCOUNT_ID"; then
  echo "Correct AWS account"
else
  echo "STOP: wrong AWS account" >&2
  return 1 2>/dev/null || exit 1
fi
```

Check credential expiry and common SAML problems without printing secrets:

```bash
aws sts get-caller-identity >/dev/null && echo "Credentials work" || echo "Refresh BU SAML credentials"
aws configure get region --profile "$AWS_PROFILE"
env | grep '^AWS_' | sed -E 's/(SECRET|TOKEN|KEY)=.*/\1=[REDACTED]/'
date
```

If your helper is installed:

```bash
command -v buaws || command -v aws-refresh
buaws
aws sts get-caller-identity
```

### BU SAML and `buaws` deep troubleshooting

`buaws` is a local helper on the Mac. It does not authenticate independently. It waits for the BU browser login and extension to produce temporary AWS STS credentials, imports them into `~/.aws/credentials`, maps the AWS account to `bu-nprd` or `bu-prd`, validates the profile with STS, and removes the temporary download.

Confirmed historical account mapping:

| BU AWS account | Local profile |
|---|---|
| `770203350335` | `bu-nprd` |
| `115619461932` | `bu-prd` |

These are BU AWS CLI profiles. They are unrelated to the application's Cognito users and tokens.

Normal non-production workflow:

```bash
open https://www.bu.edu/awslogin
buaws

unset AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY AWS_SESSION_TOKEN
export AWS_PROFILE=bu-nprd
export AWS_REGION=us-east-1
export AWS_DEFAULT_REGION=us-east-1

aws sts get-caller-identity
test "$(aws sts get-caller-identity --query Account --output text)" = 770203350335
```

Inspect what `buaws` really is without executing it:

```bash
type -a buaws
command -v buaws
ls -l "$(command -v buaws)"
file "$(command -v buaws)"
sed -n '1,260p' "$(command -v buaws)"
```

Expected global location from the historical setup:

```text
/Users/mukadder/.local/bin/buaws
```

If it is missing from `PATH`:

```bash
grep -n 'HOME/.local/bin' ~/.zshrc
export PATH="$HOME/.local/bin:$PATH"
command -v buaws
```

Inspect the credential download without printing credential values:

```bash
ls -lt ~/Downloads | head -20
file ~/Downloads/credentials 2>/dev/null

awk '
  /^\[/ {print; next}
  /^[[:space:]]*aws_(access_key_id|secret_access_key|session_token)/ {
    sub(/=.*/, "= [REDACTED]"); print; next
  }
  {print}
' ~/Downloads/credentials 2>/dev/null
```

The watcher must support both formats:

- JSON credential exports.
- Standard AWS INI, often in an extensionless file named `credentials`.

The earlier failure occurred because the watcher scanned only `.json` and `.txt` and attempted JSON parsing. The BU extension produced an extensionless AWS INI file.

Check the older background watcher only if `buaws` does not work:

```bash
launchctl print "gui/$(id -u)/edu.bu.aws-saml-watcher" 2>&1 |
  grep -E 'state =|last exit code|program =|path ='

launchctl list | grep -i 'aws\|saml'
tail -100 ~/Library/Logs/bu-aws-saml-watcher.log 2>/dev/null
tail -100 ~/Library/Logs/bu-aws-saml-watcher-error.log 2>/dev/null
```

Verify both profile existence and identity:

```bash
aws configure list-profiles | grep -E '^bu-(nprd|prd)$'
aws configure list --profile bu-nprd
aws sts get-caller-identity --profile bu-nprd
```

Common interpretations:

| Symptom | Meaning and next check |
|---|---|
| `ExpiredToken` | Log in again and rerun `buaws`. |
| `Unable to locate credentials` | Import did not complete or wrong profile name. |
| `AccessDenied` with valid STS identity | Credentials work, but the role lacks permission for that action. |
| `Operation not permitted` writing `~/.aws/credentials` | macOS privacy or file ownership/permissions issue. Prefer foreground `buaws`; inspect ownership and mode. |
| Download exists but helper ignores it | Confirm the helper supports extensionless INI and JSON. |
| Wrong AWS account | Stop. Unset environment credentials, select the correct BU account/role, rerun `buaws`, and verify STS. |

Never paste or upload the downloaded credentials file. Never display its three values in terminal output being shared.

### BU AWS SAML versus application Cognito

These two login systems solve different problems:

| System | Who uses it | What it grants |
|---|---|---|
| BU Shibboleth → AWS SAML/STS | Developer/operator | Temporary AWS CLI, Terraform and Console access through `Shibboleth-InfraMgt` |
| Cognito/JWT | Research Archive web user or API test client | Access to secured `/api/**` application endpoints |

Running `buaws` does not generate an application bearer token. Logging into Cognito does not give Terraform or AWS CLI permission.

The complete BU AWS operator flow is:

1. Open `https://www.bu.edu/awslogin`.
2. Authenticate with BU Shibboleth.
3. Select the intended account and `Shibboleth-InfraMgt` role.
4. The browser extension exchanges the SAML assertion through AWS STS.
5. The extension downloads temporary credentials to `~/Downloads/credentials`.
6. `buaws` parses JSON or AWS INI, identifies the account, updates the matching profile, validates it, and deletes the temporary file.
7. AWS CLI, Terraform, SDKs and coding agents launched from that shell use the refreshed profile.

Credentials are shared through `~/.aws/credentials`, but `AWS_PROFILE` is a per-shell selection unless exported from shell startup configuration. A safe practice is to set it explicitly in each BU terminal and verify STS before every write.

## 2. Fast high-level inventory

```bash
aws ec2 describe-vpcs \
  --vpc-ids "$RAP_VPC_ID" \
  --query 'Vpcs[].{VpcId:VpcId,Cidr:CidrBlock,State:State,Default:IsDefault,Dhcp:DhcpOptionsId,Owner:OwnerId}' \
  --output table

aws ec2 describe-vpc-attribute --vpc-id "$RAP_VPC_ID" --attribute enableDnsSupport
aws ec2 describe-vpc-attribute --vpc-id "$RAP_VPC_ID" --attribute enableDnsHostnames

aws ec2 describe-subnets \
  --filters Name=vpc-id,Values="$RAP_VPC_ID" \
  --query 'Subnets[].{Name:Tags[?Key==`Name`]|[0].Value,Id:SubnetId,CIDR:CidrBlock,AZ:AvailabilityZone,PublicIP:MapPublicIpOnLaunch,AvailableIPs:AvailableIpAddressCount}' \
  --output table

aws ec2 describe-route-tables \
  --filters Name=vpc-id,Values="$RAP_VPC_ID" \
  --query 'RouteTables[].{Name:Tags[?Key==`Name`]|[0].Value,RouteTableId:RouteTableId,Associations:Associations[].SubnetId}' \
  --output json | jq .

aws ec2 describe-security-groups \
  --filters Name=vpc-id,Values="$RAP_VPC_ID" \
  --query 'SecurityGroups[].{Name:GroupName,Id:GroupId,Description:Description}' \
  --output table

aws ec2 describe-network-acls \
  --filters Name=vpc-id,Values="$RAP_VPC_ID" \
  --query 'NetworkAcls[].{Id:NetworkAclId,Default:IsDefault,Subnets:Associations[].SubnetId}' \
  --output json | jq .

aws ec2 describe-vpc-endpoints \
  --filters Name=vpc-id,Values="$RAP_VPC_ID" \
  --query 'VpcEndpoints[].{Id:VpcEndpointId,Service:ServiceName,Type:VpcEndpointType,State:State,PrivateDNS:PrivateDnsEnabled}' \
  --output table
```

Inventory tagged resources through Resource Groups Tagging API:

```bash
aws resourcegroupstaggingapi get-resources \
  --tag-filters Key=Environment,Values=dev \
  --query 'ResourceTagMappingList[].ResourceARN' \
  --output text | tr '\t' '\n'

aws resourcegroupstaggingapi get-resources \
  --resource-type-filters ec2:vpc ec2:subnet ec2:security-group rds:db ecs:cluster ecs:service \
  --output json | jq .
```

## 3. Subnets and route tables

Show every route, target, origin, and state:

```bash
aws ec2 describe-route-tables \
  --filters Name=vpc-id,Values="$RAP_VPC_ID" \
  --output json | jq -r '
    .RouteTables[] |
    "ROUTE TABLE \(.RouteTableId) name=\([.Tags[]? | select(.Key=="Name") | .Value][0] // "-")",
    (.Associations[]? | "  association subnet=\(.SubnetId // "main") main=\(.Main // false)"),
    (.Routes[] | "  route destination=\(.DestinationCidrBlock // .DestinationIpv6CidrBlock // .DestinationPrefixListId) target=\(.GatewayId // .NatGatewayId // .TransitGatewayId // .VpcPeeringConnectionId // .NetworkInterfaceId // .InstanceId // "local") state=\(.State // "active") origin=\(.Origin)")'
```

Find the effective route table for one subnet:

```bash
export RAP_SUBNET_ID=subnet-replace-me

aws ec2 describe-route-tables \
  --filters Name=association.subnet-id,Values="$RAP_SUBNET_ID" \
  --output json | jq .

# If no explicit association is returned, inspect the VPC main table.
aws ec2 describe-route-tables \
  --filters Name=vpc-id,Values="$RAP_VPC_ID" Name=association.main,Values=true \
  --output json | jq .
```

Confirm both required Oracle `/25` routes exist and target the peering connection:

```bash
aws ec2 describe-route-tables \
  --filters Name=vpc-id,Values="$RAP_VPC_ID" \
  --query "RouteTables[].{RouteTable:RouteTableId,OracleRoutes:Routes[?VpcPeeringConnectionId=='$RAP_PEERING_ID'].{CIDR:DestinationCidrBlock,Peering:VpcPeeringConnectionId,State:State}}" \
  --output json | jq .
```

Find blackhole routes:

```bash
aws ec2 describe-route-tables \
  --filters Name=vpc-id,Values="$RAP_VPC_ID" \
  --query 'RouteTables[].{RouteTable:RouteTableId,Blackholes:Routes[?State==`blackhole`]}' \
  --output json | jq .
```

## 4. Internet gateway and NAT gateways

```bash
aws ec2 describe-internet-gateways \
  --filters Name=attachment.vpc-id,Values="$RAP_VPC_ID" \
  --query 'InternetGateways[].{Id:InternetGatewayId,State:Attachments[0].State}' \
  --output table

aws ec2 describe-nat-gateways \
  --filter Name=vpc-id,Values="$RAP_VPC_ID" \
  --query 'NatGateways[].{Name:Tags[?Key==`Name`]|[0].Value,Id:NatGatewayId,State:State,Subnet:SubnetId,PublicIP:NatGatewayAddresses[0].PublicIp,PrivateIP:NatGatewayAddresses[0].PrivateIp,Created:CreateTime,Failure:FailureMessage}' \
  --output table

aws ec2 describe-addresses \
  --filters Name=domain,Values=vpc \
  --query 'Addresses[].{AllocationId:AllocationId,PublicIP:PublicIp,PrivateIP:PrivateIpAddress,Association:AssociationId,NetworkInterface:NetworkInterfaceId}' \
  --output table
```

Check NAT CloudWatch metrics. Replace the ID with one returned above:

```bash
export RAP_NAT_ID=nat-replace-me
export RAP_START_TIME="$(date -u -v-1H +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || date -u -d '1 hour ago' +%Y-%m-%dT%H:%M:%SZ)"
export RAP_END_TIME="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

for RAP_METRIC_NAME in ActiveConnectionCount BytesInFromSource BytesOutToDestination ErrorPortAllocation PacketsDropCount; do
  aws cloudwatch get-metric-statistics \
    --namespace AWS/NATGateway \
    --metric-name "$RAP_METRIC_NAME" \
    --dimensions Name=NatGatewayId,Value="$RAP_NAT_ID" \
    --start-time "$RAP_START_TIME" \
    --end-time "$RAP_END_TIME" \
    --period 300 \
    --statistics Sum Maximum \
    --output json | jq --arg metric "$RAP_METRIC_NAME" '{metric:$metric,datapoints:.Datapoints}'
done
```

## 5. VPC peering to Oracle/Kuali

```bash
aws ec2 describe-vpc-peering-connections \
  --vpc-peering-connection-ids "$RAP_PEERING_ID" \
  --query 'VpcPeeringConnections[].{Id:VpcPeeringConnectionId,Status:Status.Code,Message:Status.Message,RequesterVpc:RequesterVpcInfo.VpcId,RequesterCIDR:RequesterVpcInfo.CidrBlock,RequesterOwner:RequesterVpcInfo.OwnerId,AccepterVpc:AccepterVpcInfo.VpcId,AccepterCIDR:AccepterVpcInfo.CidrBlock,AccepterOwner:AccepterVpcInfo.OwnerId,Expires:ExpirationTime}' \
  --output table

aws ec2 describe-vpc-peering-connections \
  --vpc-peering-connection-ids "$RAP_PEERING_ID" \
  --query 'VpcPeeringConnections[0].{RequesterOptions:RequesterVpcInfo.PeeringOptions,AccepterOptions:AccepterVpcInfo.PeeringOptions}' \
  --output json | jq .

aws ec2 describe-vpc-peering-connections \
  --filters Name=status-code,Values=pending-acceptance,failed,rejected,expired,deleted \
  --output table
```

The Research Archive side must route both Oracle ranges through the peering connection. The Oracle VPC side must also have return routes to `10.30.0.0/16`, or to narrower Research Archive source ranges if BU intentionally restricts them. Peering is not transitive: traffic cannot go through this peering connection and then through a transit gateway or another peering connection.

If your role can see the Oracle VPC account/resources:

```bash
aws ec2 describe-route-tables \
  --filters Name=vpc-id,Values="$RAP_ORACLE_VPC_ID" \
  --output json | jq -r '.RouteTables[] | .RouteTableId as $rt | .Routes[] | select(.DestinationCidrBlock=="10.30.0.0/16") | {routeTable:$rt,destination:.DestinationCidrBlock,peering:.VpcPeeringConnectionId,state:.State}'
```

If this returns `UnauthorizedOperation` or nothing because the other side belongs to a different account, ask that account's network owner to verify the return route, NACLs, and Oracle security group.

## 6. Transit gateway and BU VPN/on-premises access

```bash
aws ec2 describe-transit-gateways \
  --transit-gateway-ids "$RAP_TGW_ID" \
  --query 'TransitGateways[].{Id:TransitGatewayId,State:State,Owner:OwnerId,Description:Description,ASN:Options.AmazonSideAsn,AutoAccept:Options.AutoAcceptSharedAttachments,DefaultAssociation:Options.AssociationDefaultRouteTableId,DefaultPropagation:Options.PropagationDefaultRouteTableId}' \
  --output table

aws ec2 describe-transit-gateway-vpc-attachments \
  --filters Name=transit-gateway-id,Values="$RAP_TGW_ID" \
  --query 'TransitGatewayVpcAttachments[].{Id:TransitGatewayAttachmentId,State:State,VpcId:VpcId,Subnets:SubnetIds,Owner:VpcOwnerId,Created:CreationTime}' \
  --output json | jq .

aws ec2 describe-transit-gateway-attachments \
  --filters Name=transit-gateway-id,Values="$RAP_TGW_ID" \
  --query 'TransitGatewayAttachments[].{Id:TransitGatewayAttachmentId,Type:ResourceType,ResourceId:ResourceId,State:State,Owner:ResourceOwnerId,Association:Association}' \
  --output json | jq .

aws ec2 describe-transit-gateway-route-tables \
  --filters Name=transit-gateway-id,Values="$RAP_TGW_ID" \
  --query 'TransitGatewayRouteTables[].{Id:TransitGatewayRouteTableId,State:State,DefaultAssociation:DefaultAssociationRouteTable,DefaultPropagation:DefaultPropagationRouteTable}' \
  --output table
```

The shared transit gateway belongs to another AWS account. Your role may be allowed to see the shared TGW and your attachment but not its complete route tables.

For every visible transit gateway route table:

```bash
for RAP_TGW_RT_ID in $(aws ec2 describe-transit-gateway-route-tables \
  --filters Name=transit-gateway-id,Values="$RAP_TGW_ID" \
  --query 'TransitGatewayRouteTables[].TransitGatewayRouteTableId' \
  --output text); do
  echo "### $RAP_TGW_RT_ID"
  aws ec2 search-transit-gateway-routes \
    --transit-gateway-route-table-id "$RAP_TGW_RT_ID" \
    --filters Name=state,Values=active,blackhole \
    --max-results 1000 \
    --output json | jq .
done
```

Check associations and propagations:

```bash
for RAP_TGW_RT_ID in $(aws ec2 describe-transit-gateway-route-tables \
  --filters Name=transit-gateway-id,Values="$RAP_TGW_ID" \
  --query 'TransitGatewayRouteTables[].TransitGatewayRouteTableId' \
  --output text); do
  aws ec2 get-transit-gateway-route-table-associations \
    --transit-gateway-route-table-id "$RAP_TGW_RT_ID" \
    --output json | jq .
  aws ec2 get-transit-gateway-route-table-propagations \
    --transit-gateway-route-table-id "$RAP_TGW_RT_ID" \
    --output json | jq .
done
```

Confirm the Research Archive subnet route tables contain routes to BU networks through the TGW:

```bash
aws ec2 describe-route-tables \
  --filters Name=vpc-id,Values="$RAP_VPC_ID" \
  --output json | jq --arg tgw "$RAP_TGW_ID" '
    .RouteTables[] |
    {routeTable:.RouteTableId,associations:[.Associations[].SubnetId],tgwRoutes:[.Routes[] | select(.TransitGatewayId==$tgw)]}'
```

## 7. Security groups

Dump all ingress and egress rules in a readable form:

```bash
aws ec2 describe-security-group-rules \
  --filters Name=group-id,Values="$RAP_RDS_SG_ID" \
  --query 'SecurityGroupRules[].{RuleId:SecurityGroupRuleId,Egress:IsEgress,Protocol:IpProtocol,From:FromPort,To:ToPort,CIDR:CidrIpv4,IPv6:CidrIpv6,PrefixList:PrefixListId,ReferencedSG:ReferencedGroupInfo.GroupId,Description:Description}' \
  --output table

aws ec2 describe-security-groups \
  --filters Name=vpc-id,Values="$RAP_VPC_ID" \
  --output json | jq -r '
    .SecurityGroups[] |
    "SECURITY GROUP \(.GroupId) \(.GroupName)",
    (.IpPermissions[]? | "  IN  proto=\(.IpProtocol) ports=\(.FromPort // "all")-\(.ToPort // "all") ipv4=\([.IpRanges[].CidrIp] | join(",")) sourceSG=\([.UserIdGroupPairs[].GroupId] | join(","))"),
    (.IpPermissionsEgress[]? | "  OUT proto=\(.IpProtocol) ports=\(.FromPort // "all")-\(.ToPort // "all") ipv4=\([.IpRanges[].CidrIp] | join(",")) targetSG=\([.UserIdGroupPairs[].GroupId] | join(","))")'
```

Find every rule that permits PostgreSQL port 5432:

```bash
aws ec2 describe-security-group-rules \
  --filters Name=group-id,Values="$RAP_RDS_SG_ID" \
  --output json | jq '.SecurityGroupRules[] | select(.IsEgress==false) | select((.FromPort <= 5432 and .ToPort >= 5432) or .IpProtocol=="-1")'
```

Check for risky broad ingress:

```bash
aws ec2 describe-security-group-rules \
  --filters Name=vpc-id,Values="$RAP_VPC_ID" \
  --output json | jq '.SecurityGroupRules[] | select(.IsEgress==false and (.CidrIpv4=="0.0.0.0/0" or .CidrIpv6=="::/0"))'
```

Identify ECS task security groups before deciding whether RDS-to-ECS references are correct:

```bash
aws ecs list-clusters --output text
aws ecs list-services --cluster replace-cluster-name --output text
aws ecs describe-services \
  --cluster replace-cluster-name \
  --services replace-service-name \
  --query 'services[].networkConfiguration.awsvpcConfiguration' \
  --output json | jq .
```

For PostgreSQL connectivity, check both directions conceptually:

- The RDS SG must allow inbound TCP 5432 from the API/loader SG, or intentionally from a narrow BU CIDR.
- The task SG must allow outbound TCP 5432 to the RDS SG/IP.
- Security groups are stateful, so return traffic does not need a separate SG rule.

## 8. Network ACLs

```bash
aws ec2 describe-network-acls \
  --filters Name=vpc-id,Values="$RAP_VPC_ID" \
  --output json | jq -r '
    .NetworkAcls[] |
    "NACL \(.NetworkAclId) default=\(.IsDefault) subnets=\([.Associations[].SubnetId] | join(","))",
    (.Entries | sort_by(.Egress,.RuleNumber)[] | "  egress=\(.Egress) rule=\(.RuleNumber) action=\(.RuleAction) proto=\(.Protocol) cidr=\(.CidrBlock // .Ipv6CidrBlock) ports=\(.PortRange.From // "all")-\(.PortRange.To // "all")")'
```

Find the NACL attached to a subnet:

```bash
aws ec2 describe-network-acls \
  --filters Name=association.subnet-id,Values="$RAP_SUBNET_ID" \
  --output json | jq .
```

NACLs are stateless. A TCP connection needs the destination port allowed in one direction and ephemeral return ports, commonly `1024-65535`, allowed in the other direction. Check both the source and destination subnets.

## 9. Elastic network interfaces and private IP ownership

Find what owns an IP:

```bash
export RAP_PRIVATE_IP=10.30.12.216

aws ec2 describe-network-interfaces \
  --filters Name=addresses.private-ip-address,Values="$RAP_PRIVATE_IP" \
  --query 'NetworkInterfaces[].{ENI:NetworkInterfaceId,Description:Description,Status:Status,Type:InterfaceType,Subnet:SubnetId,Vpc:VpcId,PrivateIP:PrivateIpAddress,Groups:Groups,Attachment:Attachment}' \
  --output json | jq .
```

Show all interfaces in the VPC:

```bash
aws ec2 describe-network-interfaces \
  --filters Name=vpc-id,Values="$RAP_VPC_ID" \
  --query 'NetworkInterfaces[].{ENI:NetworkInterfaceId,Description:Description,Status:Status,Type:InterfaceType,Subnet:SubnetId,IP:PrivateIpAddress,Groups:Groups[].GroupId}' \
  --output table
```

Show ECS task ENIs:

```bash
export RAP_CLUSTER=replace-cluster-name
export RAP_SERVICE=replace-service-name

aws ecs list-tasks --cluster "$RAP_CLUSTER" --service-name "$RAP_SERVICE" --output text
aws ecs describe-tasks \
  --cluster "$RAP_CLUSTER" \
  --tasks $(aws ecs list-tasks --cluster "$RAP_CLUSTER" --service-name "$RAP_SERVICE" --query 'taskArns[]' --output text) \
  --query 'tasks[].{Task:taskArn,Last:lastStatus,Health:healthStatus,AZ:availabilityZone,ENI:attachments[].details[?name==`networkInterfaceId`].value|[0],PrivateIP:attachments[].details[?name==`privateIPv4Address`].value|[0],StoppedReason:stoppedReason}' \
  --output table
```

## 10. RDS PostgreSQL

Discover the current DB identifier from the endpoint:

```bash
aws rds describe-db-instances \
  --query "DBInstances[?Endpoint.Address=='$RAP_RDS_HOST'].{Id:DBInstanceIdentifier,Status:DBInstanceStatus,Engine:Engine,Version:EngineVersion,Endpoint:Endpoint.Address,Port:Endpoint.Port,AZ:AvailabilityZone,MultiAZ:MultiAZ,Public:PubliclyAccessible,Vpc:DBSubnetGroup.VpcId,SubnetGroup:DBSubnetGroup.DBSubnetGroupName,Subnets:DBSubnetGroup.Subnets[].SubnetIdentifier,SGs:VpcSecurityGroups[].VpcSecurityGroupId,Encrypted:StorageEncrypted,KmsKey:KmsKeyId,BackupRetention:BackupRetentionPeriod,LatestRestore:LatestRestorableTime,Certificate:CACertificateIdentifier,Pending:PendingModifiedValues}" \
  --output json | jq .

export RAP_DB_ID="$(aws rds describe-db-instances \
  --query "DBInstances[?Endpoint.Address=='$RAP_RDS_HOST'].DBInstanceIdentifier | [0]" \
  --output text)"
echo "$RAP_DB_ID"
```

If the diagram endpoint is stale, find likely development PostgreSQL instances:

```bash
aws rds describe-db-instances \
  --query 'DBInstances[?Engine==`postgres`].{Id:DBInstanceIdentifier,Status:DBInstanceStatus,Endpoint:Endpoint.Address,Port:Endpoint.Port,Vpc:DBSubnetGroup.VpcId,Public:PubliclyAccessible,SGs:VpcSecurityGroups[].VpcSecurityGroupId}' \
  --output table
```

Inspect subnet group, parameter group, option group, and recent events:

```bash
aws rds describe-db-subnet-groups \
  --db-subnet-group-name "$(aws rds describe-db-instances --db-instance-identifier "$RAP_DB_ID" --query 'DBInstances[0].DBSubnetGroup.DBSubnetGroupName' --output text)" \
  --output json | jq .

aws rds describe-db-parameters \
  --db-parameter-group-name "$(aws rds describe-db-instances --db-instance-identifier "$RAP_DB_ID" --query 'DBInstances[0].DBParameterGroups[0].DBParameterGroupName' --output text)" \
  --source user \
  --output json | jq .

aws rds describe-events \
  --source-type db-instance \
  --source-identifier "$RAP_DB_ID" \
  --duration 1440 \
  --output table

aws rds describe-db-log-files \
  --db-instance-identifier "$RAP_DB_ID" \
  --output table
```

Download only the tail of a selected PostgreSQL log:

```bash
export RAP_DB_LOG_FILE=replace-with-log-filename
aws rds download-db-log-file-portion \
  --db-instance-identifier "$RAP_DB_ID" \
  --log-file-name "$RAP_DB_LOG_FILE" \
  --number-of-lines 200 \
  --output text
```

Check key CloudWatch DB metrics over the last hour:

```bash
for RAP_METRIC_NAME in CPUUtilization DatabaseConnections FreeableMemory FreeStorageSpace ReadLatency WriteLatency DiskQueueDepth; do
  aws cloudwatch get-metric-statistics \
    --namespace AWS/RDS \
    --metric-name "$RAP_METRIC_NAME" \
    --dimensions Name=DBInstanceIdentifier,Value="$RAP_DB_ID" \
    --start-time "$RAP_START_TIME" \
    --end-time "$RAP_END_TIME" \
    --period 300 \
    --statistics Average Maximum \
    --output json | jq --arg metric "$RAP_METRIC_NAME" '{metric:$metric,datapoints:.Datapoints}'
done
```

## 11. Local DNS and TCP tests

These tests reveal whether your Mac can resolve or route to a private endpoint. They do not prove that ECS can connect.

```bash
dig +short "$RAP_RDS_HOST"
nslookup "$RAP_RDS_HOST"
host "$RAP_RDS_HOST"

nc -vz -G 5 "$RAP_RDS_HOST" "$RAP_RDS_PORT"        # macOS
nc -vz -w 5 "$RAP_RDS_HOST" "$RAP_RDS_PORT"        # Linux

route -n get 10.30.12.216                             # macOS
traceroute -n 10.30.12.216                            # macOS/Linux
```

Inspect VPN interfaces and routes on macOS:

```bash
ifconfig
netstat -rn -f inet
scutil --dns
scutil --proxy
route -n get 10.30.12.216
route -n get 10.58.33.1
```

Check the public IP visible to AWS-safe external services only if BU policy permits:

```bash
curl -sS https://checkip.amazonaws.com
```

Test PostgreSQL protocol without exposing a password:

```bash
PGCONNECT_TIMEOUT=5 psql \
  "host=$RAP_RDS_HOST port=$RAP_RDS_PORT dbname=$POSTGRES_DB user=$POSTGRES_USER sslmode=require" \
  -c 'select current_database(), current_user, inet_server_addr(), inet_server_port(), now();'
```

Interpretation:

| Symptom | Likely layer |
|---|---|
| DNS name does not resolve | DNS/VPN resolver or stale endpoint |
| DNS resolves but `nc` times out | route, TGW/VPN, SG, NACL, or DB unavailable |
| `nc` succeeds but `psql` fails authentication | database credentials/user/database name |
| `psql` reports certificate problem | TLS mode or CA trust |
| Mac fails but ECS succeeds | expected if RDS is reachable only from VPC tasks |
| ECS fails but Mac succeeds | ECS subnet route or task SG problem |

## 12. ECS clusters, services, tasks, and deployments

```bash
aws ecs list-clusters --output table

for RAP_CLUSTER_ARN in $(aws ecs list-clusters --query 'clusterArns[]' --output text); do
  aws ecs describe-clusters \
    --clusters "$RAP_CLUSTER_ARN" \
    --include SETTINGS STATISTICS TAGS \
    --output json | jq .
done
```

Once `RAP_CLUSTER` is known:

```bash
aws ecs list-services --cluster "$RAP_CLUSTER" --output table

aws ecs describe-services \
  --cluster "$RAP_CLUSTER" \
  --services $(aws ecs list-services --cluster "$RAP_CLUSTER" --query 'serviceArns[]' --output text) \
  --query 'services[].{Name:serviceName,Status:status,Desired:desiredCount,Running:runningCount,Pending:pendingCount,LaunchType:launchType,Platform:platformVersion,TaskDefinition:taskDefinition,ExecuteCommand:enableExecuteCommand,Network:networkConfiguration,Deployments:deployments,Events:events[0:10]}' \
  --output json | jq .

aws ecs list-tasks --cluster "$RAP_CLUSTER" --desired-status RUNNING --output table
aws ecs list-tasks --cluster "$RAP_CLUSTER" --desired-status STOPPED --output table
```

Inspect stopped tasks and failures:

```bash
RAP_STOPPED_TASKS="$(aws ecs list-tasks --cluster "$RAP_CLUSTER" --desired-status STOPPED --query 'taskArns[0:20]' --output text)"
if [ -n "$RAP_STOPPED_TASKS" ]; then
  aws ecs describe-tasks \
    --cluster "$RAP_CLUSTER" \
    --tasks $RAP_STOPPED_TASKS \
    --query 'tasks[].{Task:taskArn,StoppedAt:stoppedAt,StopCode:stopCode,StoppedReason:stoppedReason,Containers:containers[].{Name:name,Reason:reason,Exit:exitCode,Last:lastStatus}}' \
    --output json | jq .
fi
```

Inspect a task definition without printing secret values. ECS shows references, not Secrets Manager secret contents:

```bash
export RAP_TASK_DEFINITION=replace-task-definition-arn

aws ecs describe-task-definition \
  --task-definition "$RAP_TASK_DEFINITION" \
  --include TAGS \
  --query 'taskDefinition.{Family:family,Revision:revision,CPU:cpu,Memory:memory,ExecutionRole:executionRoleArn,TaskRole:taskRoleArn,NetworkMode:networkMode,Containers:containerDefinitions[].{Name:name,Image:image,CPU:cpu,Memory:memory,Ports:portMappings,HealthCheck:healthCheck,Logs:logConfiguration,Environment:environment[].name,SecretNames:secrets[].name,ReadonlyRoot:readonlyRootFilesystem}}' \
  --output json | jq .
```

Check whether required environment variable names exist:

```bash
aws ecs describe-task-definition \
  --task-definition "$RAP_TASK_DEFINITION" \
  --output json | jq -r '.taskDefinition.containerDefinitions[] | .name as $container | (.environment[]?.name),(.secrets[]?.name) | "\($container):\(.)"' | sort
```

Look for `ARCHIVE_DOCUMENTS_BUCKET`, database variables or secret references, Cognito issuer/client configuration, and the expected Spring profile.

## 13. CloudWatch Logs

```bash
aws logs describe-log-groups \
  --log-group-name-prefix /ecs/ \
  --query 'logGroups[].{Name:logGroupName,Retention:retentionInDays,StoredBytes:storedBytes,KmsKey:kmsKeyId}' \
  --output table

aws logs describe-log-streams \
  --log-group-name replace-log-group \
  --order-by LastEventTime \
  --descending \
  --max-items 20 \
  --output table

aws logs tail replace-log-group --since 1h --format short
aws logs tail replace-log-group --since 30m --follow --format short
```

Filter likely application errors:

```bash
aws logs filter-log-events \
  --log-group-name replace-log-group \
  --start-time "$(($(date +%s)-3600))000" \
  --filter-pattern '?ERROR ?Exception ?Caused ?FATAL ?timeout ?denied' \
  --query 'events[].message' \
  --output text
```

CloudWatch Logs Insights query through CLI:

```bash
export RAP_LOG_GROUP=replace-log-group
export RAP_QUERY_ID="$(aws logs start-query \
  --log-group-name "$RAP_LOG_GROUP" \
  --start-time "$(($(date +%s)-3600))" \
  --end-time "$(date +%s)" \
  --query-string 'fields @timestamp, @message | filter @message like /ERROR|Exception|timeout|denied/ | sort @timestamp desc | limit 100' \
  --query queryId --output text)"

aws logs get-query-results --query-id "$RAP_QUERY_ID" --output json | jq .
```

## 14. ECR image troubleshooting

```bash
aws ecr describe-repositories \
  --query 'repositories[].{Name:repositoryName,URI:repositoryUri,Created:createdAt,ScanOnPush:imageScanningConfiguration.scanOnPush,Encryption:encryptionConfiguration.encryptionType}' \
  --output table

aws ecr describe-images \
  --repository-name replace-repository \
  --query 'sort_by(imageDetails,&imagePushedAt)[-20:].{Tags:imageTags,Digest:imageDigest,Pushed:imagePushedAt,Size:imageSizeInBytes,Scan:imageScanStatus.status}' \
  --output table

aws ecr get-repository-policy --repository-name replace-repository --output json | jq .
aws ecr get-lifecycle-policy --repository-name replace-repository --output json | jq .
```

Confirm the task definition image exists:

```bash
aws ecs describe-task-definition \
  --task-definition "$RAP_TASK_DEFINITION" \
  --query 'taskDefinition.containerDefinitions[].image' \
  --output text
```

Test ECR authentication without building or pushing:

```bash
aws ecr get-login-password >/dev/null && echo "ECR authorization works"
```

Docker login changes local Docker credentials but does not change AWS infrastructure:

```bash
aws ecr get-login-password \
  | docker login --username AWS --password-stdin "$RAP_ACCOUNT_ID.dkr.ecr.$AWS_REGION.amazonaws.com"
```

## 15. S3 archive buckets

Do not use `aws s3 sync`, `cp`, `mv`, or `rm` during diagnosis.

```bash
aws s3api list-buckets --query 'Buckets[].{Name:Name,Created:CreationDate}' --output table

export RAP_BUCKET=replace-bucket-name

aws s3api head-bucket --bucket "$RAP_BUCKET"
aws s3api get-bucket-location --bucket "$RAP_BUCKET"
aws s3api get-bucket-versioning --bucket "$RAP_BUCKET"
aws s3api get-public-access-block --bucket "$RAP_BUCKET"
aws s3api get-bucket-encryption --bucket "$RAP_BUCKET"
aws s3api get-bucket-policy-status --bucket "$RAP_BUCKET"
aws s3api get-bucket-lifecycle-configuration --bucket "$RAP_BUCKET"
aws s3api get-bucket-logging --bucket "$RAP_BUCKET"
aws s3api get-bucket-notification-configuration --bucket "$RAP_BUCKET"
aws s3api get-bucket-cors --bucket "$RAP_BUCKET"

aws s3api list-objects-v2 \
  --bucket "$RAP_BUCKET" \
  --max-items 20 \
  --query 'Contents[].{Key:Key,Size:Size,Modified:LastModified,Class:StorageClass}' \
  --output table
```

Check a known object without downloading it:

```bash
aws s3api head-object --bucket "$RAP_BUCKET" --key 'replace/object/key' --output json | jq .
```

## 16. Secrets Manager and KMS metadata

Never run `get-secret-value` merely to troubleshoot inventory. It prints sensitive data.

```bash
aws secretsmanager list-secrets \
  --query 'SecretList[].{Name:Name,ARN:ARN,Description:Description,KmsKeyId:KmsKeyId,LastChanged:LastChangedDate,LastAccessed:LastAccessedDate,Rotation:RotationEnabled}' \
  --output table

aws secretsmanager describe-secret \
  --secret-id replace-secret-name-or-arn \
  --query '{Name:Name,ARN:ARN,KmsKeyId:KmsKeyId,RotationEnabled:RotationEnabled,LastChanged:LastChangedDate,VersionIdsToStages:VersionIdsToStages,DeletedDate:DeletedDate}' \
  --output json | jq .

aws kms list-aliases --output table
aws kms describe-key --key-id replace-kms-key-id --output json | jq '.KeyMetadata | {KeyId,Arn,Enabled,KeyState,KeyUsage,Origin,KeyManager,Description}'
aws kms get-key-policy --key-id replace-kms-key-id --policy-name default --output json | jq .
```

Check which task roles are supposed to retrieve secrets and access buckets:

```bash
aws ecs describe-task-definition \
  --task-definition "$RAP_TASK_DEFINITION" \
  --query 'taskDefinition.{ExecutionRole:executionRoleArn,TaskRole:taskRoleArn,Secrets:containerDefinitions[].secrets,LogConfig:containerDefinitions[].logConfiguration}' \
  --output json | jq .
```

Remember: the execution role normally pulls images and injects secrets; the task role authorizes application runtime calls such as S3 access.

## 17. IAM role and policy diagnosis

```bash
export RAP_ROLE_NAME=replace-role-name

aws iam get-role --role-name "$RAP_ROLE_NAME" --output json | jq '.Role | {RoleName,Arn,CreateDate,MaxSessionDuration,AssumeRolePolicyDocument}'
aws iam list-attached-role-policies --role-name "$RAP_ROLE_NAME" --output table
aws iam list-role-policies --role-name "$RAP_ROLE_NAME" --output table
```

Read attached managed policies:

```bash
for RAP_POLICY_ARN in $(aws iam list-attached-role-policies \
  --role-name "$RAP_ROLE_NAME" \
  --query 'AttachedPolicies[].PolicyArn' --output text); do
  RAP_POLICY_VERSION="$(aws iam get-policy --policy-arn "$RAP_POLICY_ARN" --query 'Policy.DefaultVersionId' --output text)"
  aws iam get-policy-version \
    --policy-arn "$RAP_POLICY_ARN" \
    --version-id "$RAP_POLICY_VERSION" \
    --query 'PolicyVersion.Document' \
    --output json | jq .
done
```

Read inline policies:

```bash
for RAP_POLICY_NAME in $(aws iam list-role-policies \
  --role-name "$RAP_ROLE_NAME" \
  --query 'PolicyNames[]' --output text); do
  aws iam get-role-policy \
    --role-name "$RAP_ROLE_NAME" \
    --policy-name "$RAP_POLICY_NAME" \
    --output json | jq .
done
```

Simulate specific actions only if your role permits IAM simulation:

```bash
aws iam simulate-principal-policy \
  --policy-source-arn "arn:aws:iam::$RAP_ACCOUNT_ID:role/$RAP_ROLE_NAME" \
  --action-names s3:GetObject s3:ListBucket secretsmanager:GetSecretValue logs:CreateLogStream logs:PutLogEvents \
  --resource-arns '*' \
  --output json | jq '.EvaluationResults[] | {Action:EvalActionName,Decision:EvalDecision,MissingContext:MissingContextValues}'
```

An explicit deny can come from an identity policy, permissions boundary, session policy, service control policy, resource policy, VPC endpoint policy, or KMS key policy.

## 18. Cognito/JWT

```bash
aws cognito-idp list-user-pools \
  --max-results 60 \
  --query 'UserPools[].{Name:Name,Id:Id,Status:Status,LastModified:LastModifiedDate}' \
  --output table

export RAP_USER_POOL_ID=us-east-1_KnifXAgWm
export RAP_CLIENT_ID=4svvnli76o8j2qtekkvasq7agc

aws cognito-idp describe-user-pool \
  --user-pool-id "$RAP_USER_POOL_ID" \
  --query 'UserPool.{Id:Id,Name:Name,Status:Status,Endpoint:Endpoint,DeletionProtection:DeletionProtection,Mfa:MfaConfiguration,Schema:SchemaAttributes[].Name,Domain:Domain,CustomDomain:CustomDomain}' \
  --output json | jq .

aws cognito-idp describe-user-pool-client \
  --user-pool-id "$RAP_USER_POOL_ID" \
  --client-id "$RAP_CLIENT_ID" \
  --query 'UserPoolClient.{ClientName:ClientName,ClientId:ClientId,GenerateSecret:GenerateSecret,Flows:AllowedOAuthFlows,Scopes:AllowedOAuthScopes,CallbackURLs:CallbackURLs,LogoutURLs:LogoutURLs,Providers:SupportedIdentityProviders,TokenValidityUnits:TokenValidityUnits,AccessTokenValidity:AccessTokenValidity,IdTokenValidity:IdTokenValidity,RefreshTokenValidity:RefreshTokenValidity,PreventUserExistenceErrors:PreventUserExistenceErrors}' \
  --output json | jq .

aws cognito-idp describe-user-pool-domain --domain replace-domain-prefix --output json | jq .
aws cognito-idp list-user-pool-clients --user-pool-id "$RAP_USER_POOL_ID" --max-results 60 --output table
```

Verify the issuer metadata without weakening authentication:

```bash
export RAP_ISSUER="https://cognito-idp.$AWS_REGION.amazonaws.com/$RAP_USER_POOL_ID"
curl -fsS "$RAP_ISSUER/.well-known/openid-configuration" | jq .
curl -fsS "$RAP_ISSUER/.well-known/jwks.json" | jq '{keyCount:(.keys|length),kids:[.keys[].kid]}'
```

Do not paste JWTs into commands that will be saved in shell history. If testing an endpoint, load the token silently:

```bash
read -s RAP_ACCESS_TOKEN
echo
curl -i -H "Authorization: Bearer $RAP_ACCESS_TOKEN" https://replace-api-host/api/health
unset RAP_ACCESS_TOKEN
```

## 19. VPC Flow Logs

Check whether flow logging already exists:

```bash
aws ec2 describe-flow-logs \
  --filter Name=resource-id,Values="$RAP_VPC_ID" \
  --query 'FlowLogs[].{Id:FlowLogId,Status:FlowLogStatus,Traffic:TrafficType,DestinationType:LogDestinationType,Destination:LogDestination,Format:LogFormat,MaxAggregation:MaxAggregationInterval}' \
  --output table

aws ec2 describe-flow-logs \
  --filter Name=resource-type,Values=NetworkInterface \
  --output json | jq .
```

If flow logs go to CloudWatch, identify the log group and query rejected PostgreSQL traffic:

```bash
export RAP_FLOW_LOG_GROUP=replace-flow-log-group

export RAP_FLOW_QUERY_ID="$(aws logs start-query \
  --log-group-name "$RAP_FLOW_LOG_GROUP" \
  --start-time "$(($(date +%s)-3600))" \
  --end-time "$(date +%s)" \
  --query-string 'fields @timestamp, srcAddr, srcPort, dstAddr, dstPort, action, protocol | filter dstPort = 5432 or srcPort = 5432 | sort @timestamp desc | limit 200' \
  --query queryId --output text)"

aws logs get-query-results --query-id "$RAP_FLOW_QUERY_ID" --output json | jq .
```

Flow-log interpretation:

- `REJECT` usually points to a security group or NACL.
- No flow record can mean traffic never reached that ENI/subnet, the wrong interface was queried, aggregation has not completed, or logging is not enabled for that resource.
- `ACCEPT` proves the network controls accepted packets, not that PostgreSQL or the application accepted the session.

## 20. CloudTrail: who changed what?

Recent infrastructure-related events:

```bash
aws cloudtrail lookup-events \
  --start-time "$(date -u -v-1d +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || date -u -d '1 day ago' +%Y-%m-%dT%H:%M:%SZ)" \
  --end-time "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
  --max-results 50 \
  --output json | jq -r '.Events[] | [.EventTime,.EventName,.Username,.Resources[0].ResourceName] | @tsv'
```

Look up changes to a specific resource:

```bash
aws cloudtrail lookup-events \
  --lookup-attributes AttributeKey=ResourceName,AttributeValue="$RAP_RDS_SG_ID" \
  --max-results 50 \
  --output json | jq -r '.Events[] | [.EventTime,.EventName,.Username,.CloudTrailEvent] | @tsv'

aws cloudtrail lookup-events \
  --lookup-attributes AttributeKey=ResourceName,AttributeValue="$RAP_PEERING_ID" \
  --max-results 50 \
  --output json | jq .
```

Search common network mutation events:

```bash
for RAP_EVENT_NAME in AuthorizeSecurityGroupIngress RevokeSecurityGroupIngress ModifySecurityGroupRules CreateRoute ReplaceRoute DeleteRoute CreateNetworkAclEntry ReplaceNetworkAclEntry DeleteNetworkAclEntry ModifyVpcPeeringConnectionOptions; do
  aws cloudtrail lookup-events \
    --lookup-attributes AttributeKey=EventName,AttributeValue="$RAP_EVENT_NAME" \
    --max-results 20 \
    --query 'Events[].{Time:EventTime,Event:EventName,User:Username,Resources:Resources}' \
    --output json | jq .
done
```

## 21. AWS Config, if enabled

```bash
aws configservice describe-configuration-recorders --output json | jq .
aws configservice describe-configuration-recorder-status --output table

aws configservice get-resource-config-history \
  --resource-type AWS::EC2::SecurityGroup \
  --resource-id "$RAP_RDS_SG_ID" \
  --limit 10 \
  --output json | jq .

aws configservice get-resource-config-history \
  --resource-type AWS::EC2::RouteTable \
  --resource-id replace-route-table-id \
  --limit 10 \
  --output json | jq .
```

## 22. Terraform diagnosis, without changing infrastructure

From the repository:

```bash
pwd
git status --short --branch
git remote -v
terraform version
find terraform -maxdepth 3 -type f -print | sort
```

Inspect configuration without printing `.tfvars` secrets:

```bash
rg -n 'backend|bucket|key|region|profile|vpc|subnet|route|peering|transit_gateway|security_group|cognito|ecs|rds' terraform \
  --glob '*.tf' --glob '*.hcl' --glob '*.md'
```

Safe local formatting and validation:

```bash
terraform -chdir=terraform/environments/dev fmt -check -recursive
terraform -chdir=terraform/environments/dev init -backend=false
terraform -chdir=terraform/environments/dev validate
```

Backend identity checks. Read `backend.hcl` first and confirm its bucket belongs to the current account:

```bash
sed -n '1,200p' terraform/environments/dev/backend.hcl
aws sts get-caller-identity
aws s3api get-bucket-location --bucket replace-state-bucket
aws s3api head-object \
  --bucket replace-state-bucket \
  --key research-archive-platform/environments/dev/terraform.tfstate
```

Only after confirming the correct account and backend:

```bash
terraform -chdir=terraform/environments/dev init \
  -backend-config=backend.hcl \
  -reconfigure

terraform -chdir=terraform/environments/dev providers
terraform -chdir=terraform/environments/dev workspace show
terraform -chdir=terraform/environments/dev state list
terraform -chdir=terraform/environments/dev show -no-color
terraform -chdir=terraform/environments/dev output
```

Generate a read-only comparison plan, never an apply:

```bash
terraform -chdir=terraform/environments/dev plan \
  -refresh-only \
  -lock-timeout=30s \
  -out=/tmp/rap-dev-refresh.tfplan

terraform -chdir=terraform/environments/dev show \
  -no-color /tmp/rap-dev-refresh.tfplan
```

Do not run `terraform apply`, `destroy`, `state rm`, `state mv`, `import`, `force-unlock`, or a normal refresh-changing operation unless the change is explicitly authorized and reviewed.

## 23. Active diagnostics that create temporary sessions or analysis objects

These are not part of the read-only inventory. Use them deliberately.

### SSM port forwarding to RDS

This requires an existing managed EC2 instance with network access to RDS and IAM permission to start an SSM session. It does not open RDS publicly.

```bash
aws ssm describe-instance-information \
  --query 'InstanceInformationList[].{InstanceId:InstanceId,Ping:PingStatus,Platform:PlatformName,Version:AgentVersion,LastPing:LastPingDateTime}' \
  --output table

export RAP_SSM_INSTANCE_ID=i-replace-me

aws ssm start-session \
  --target "$RAP_SSM_INSTANCE_ID" \
  --document-name AWS-StartPortForwardingSessionToRemoteHost \
  --parameters "host=$RAP_RDS_HOST,portNumber=$RAP_RDS_PORT,localPortNumber=15432"
```

In another terminal:

```bash
nc -vz 127.0.0.1 15432
PGCONNECT_TIMEOUT=5 psql \
  "host=127.0.0.1 port=15432 dbname=$POSTGRES_DB user=$POSTGRES_USER sslmode=require" \
  -c 'select now(), inet_server_addr();'
```

### ECS Exec

ECS Exec must already be enabled on the service/task and requires Session Manager support and IAM permissions.

```bash
export RAP_TASK_ARN=replace-task-arn
export RAP_CONTAINER=replace-container-name

aws ecs execute-command \
  --cluster "$RAP_CLUSTER" \
  --task "$RAP_TASK_ARN" \
  --container "$RAP_CONTAINER" \
  --interactive \
  --command '/bin/sh'
```

Inside the container, use tools already present:

```bash
env | sed -E 's/(PASSWORD|SECRET|TOKEN|KEY)=.*/\1=[REDACTED]/'
getent hosts "$RAP_RDS_HOST" || nslookup "$RAP_RDS_HOST"
nc -vz -w 5 "$RAP_RDS_HOST" 5432
```

### Reachability Analyzer

Creating a path and analysis writes temporary diagnostic resources. Resolve ENI IDs first, then create the path only with permission.

```bash
export RAP_SOURCE_ENI=eni-replace-ecs-task-eni
export RAP_DEST_ENI=eni-replace-rds-eni

export RAP_PATH_ID="$(aws ec2 create-network-insights-path \
  --source "$RAP_SOURCE_ENI" \
  --destination "$RAP_DEST_ENI" \
  --protocol tcp \
  --destination-port 5432 \
  --tag-specifications 'ResourceType=network-insights-path,Tags=[{Key=Name,Value=rap-temporary-postgres-diagnostic}]' \
  --query NetworkInsightsPath.NetworkInsightsPathId \
  --output text)"

export RAP_ANALYSIS_ID="$(aws ec2 start-network-insights-analysis \
  --network-insights-path-id "$RAP_PATH_ID" \
  --query NetworkInsightsAnalysis.NetworkInsightsAnalysisId \
  --output text)"

aws ec2 describe-network-insights-analyses \
  --network-insights-analysis-ids "$RAP_ANALYSIS_ID" \
  --output json | jq .
```

After saving the evidence, delete the temporary analysis and path:

```bash
aws ec2 delete-network-insights-analysis --network-insights-analysis-id "$RAP_ANALYSIS_ID"
aws ec2 delete-network-insights-path --network-insights-path-id "$RAP_PATH_ID"
```

## 24. One-command diagnostic snapshot

This creates a local text report. It does not change AWS. Review it before sharing because resource names, account IDs, and internal IPs may be BU-sensitive.

```bash
export RAP_REPORT="/tmp/rap-aws-diagnostic-$(date +%Y%m%d-%H%M%S).txt"

{
  echo '# Identity'
  date -u
  aws sts get-caller-identity

  echo '# VPC'
  aws ec2 describe-vpcs --vpc-ids "$RAP_VPC_ID"

  echo '# Subnets'
  aws ec2 describe-subnets --filters Name=vpc-id,Values="$RAP_VPC_ID"

  echo '# Routes'
  aws ec2 describe-route-tables --filters Name=vpc-id,Values="$RAP_VPC_ID"

  echo '# Security groups'
  aws ec2 describe-security-groups --filters Name=vpc-id,Values="$RAP_VPC_ID"

  echo '# NACLs'
  aws ec2 describe-network-acls --filters Name=vpc-id,Values="$RAP_VPC_ID"

  echo '# NAT gateways'
  aws ec2 describe-nat-gateways --filter Name=vpc-id,Values="$RAP_VPC_ID"

  echo '# Peering'
  aws ec2 describe-vpc-peering-connections --vpc-peering-connection-ids "$RAP_PEERING_ID"

  echo '# TGW attachments'
  aws ec2 describe-transit-gateway-attachments --filters Name=transit-gateway-id,Values="$RAP_TGW_ID"

  echo '# RDS'
  aws rds describe-db-instances --query "DBInstances[?DBSubnetGroup.VpcId=='$RAP_VPC_ID']"

  echo '# ECS clusters'
  aws ecs list-clusters

  echo '# Flow logs'
  aws ec2 describe-flow-logs --filter Name=resource-id,Values="$RAP_VPC_ID"
} >"$RAP_REPORT" 2>&1

echo "$RAP_REPORT"
wc -l "$RAP_REPORT"
```

## 25. Project script map: what to run and when

This map was reconciled against the GitHub repository at commit `2e69577`, in addition to the pasted terminal history. Always inspect the checked-out branch before running a script because the repository may have changed since that commit.

```bash
cd ~/projects/research-archive-platform

find scripts ops -maxdepth 2 -type f \( -name '*.sh' -o -name '*.py' \) -print 2>/dev/null | sort
git status --short
git rev-parse --short HEAD
```

Safe static inspection:

```bash
for script_file in \
  scripts/run-local.sh \
  scripts/run-award-loader.sh \
  scripts/run-award-attachment-loader.sh \
  scripts/run-archive-explorer.sh \
  scripts/get-access-token.sh \
  scripts/setup-local.sh \
  scripts/dev-deploy.sh \
  scripts/start-db-tunnel.sh \
  ops/deploy-api.sh \
  ops/logs-api.sh \
  api/scripts/dev.sh
do
  test -f "$script_file" || continue
  echo "===== $script_file ====="
  file "$script_file"
  bash -n "$script_file" && echo "syntax: OK"
  sed -n '1,280p' "$script_file"
done
```

Search for risky actions and hard-coded accounts without executing anything:

```bash
rg -n \
  'terraform (apply|destroy)|aws (ecs update-service|amplify start-job|ec2 create-|ec2 authorize-|cognito-idp admin-|s3 rm)|docker push|git push|ACCOUNT_ID|589744711110|770203350335' \
  scripts ops terraform .github 2>/dev/null
```

| Script | Purpose | Normal use | Risk |
|---|---|---|---|
| `scripts/dev-deploy.sh --check-only` | Run backend tests and frontend checks without deployment | Preflight before any full dev deployment | Safe validation mode |
| `ops/deploy-api.sh --check-only` | Validate AWS account, region, ECR and ECS targets | API deployment preflight | Safe validation mode |
| `scripts/start-db-tunnel.sh --check-only` | Determine whether an approved SSM database tunnel is possible | Tunnel preflight | Read-only discovery |
| `ops/logs-api.sh` | Follow API CloudWatch logs | API diagnosis | Read-only; long-running until `Ctrl+C` |
| `scripts/get-access-token.sh` | Obtain a Cognito access token for API testing | Secured endpoint tests | Authentication only; protect the returned token |
| `scripts/setup-local.sh` | Create synthetic attachment fixtures locally | Local attachment development | Modifies local PostgreSQL only |
| `scripts/run-award-loader.sh` | Build/run Award metadata ETL as an ECS one-off task | Migrate, create batch, inspect batch, dry-run, real load | Writes ECR/ECS/RDS during real execution |
| `scripts/run-award-attachment-loader.sh` | Separate attachment schema/load/upload workflow | Migrate-only, targeted dry-run, attachment load | Writes ECR/ECS/RDS/S3 depending on flags |
| `scripts/run-archive-explorer.sh` | Query archived PostgreSQL records with an ECS one-off task | Award, workflow-document or unit investigation | Data query is read-only, but registers an ECS task-definition revision and starts a task |
| `scripts/start-db-tunnel.sh` | Attempt local forwarding to private PostgreSQL | Only if a verified SSM managed instance can reach RDS | Starts a temporary session; historical project design has no dedicated bastion |
| `ops/deploy-api.sh` | Build, push, register and deploy API image | API deployment after validation-only mode passes | Changes ECR, ECS task definition and service |
| `scripts/dev-deploy.sh` | Test, deploy API, push Git and trigger Amplify | Intentional full dev release | Major change across code and AWS |
| `api/scripts/dev.sh` | Intended local API helper | Do not use until its path calculation is fixed | Appears broken at commit `2e69577` |
| `buaws` | Refresh local BU AWS CLI profiles | Before AWS work or after STS expiry | Rewrites local AWS credential profiles, but does not modify cloud infrastructure |

### Recommended order at the start of AWS work

```bash
cd ~/projects/research-archive-platform

buaws
unset AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY AWS_SESSION_TOKEN
export AWS_PROFILE=bu-nprd
export AWS_REGION=us-east-1

aws sts get-caller-identity
test "$(aws sts get-caller-identity --query Account --output text)" = "770203350335" \
  || { echo "Wrong AWS account; stop"; exit 1; }

./ops/deploy-api.sh --check-only
./scripts/dev-deploy.sh --check-only
./scripts/start-db-tunnel.sh --check-only
```

The `buaws` command authenticates the AWS CLI through BU SAML. It does not create a Cognito bearer token for the application.

### `run-local.sh`: local development

```bash
cd ~/projects/research-archive-platform

lsof -nP -iTCP:8080 -sTCP:LISTEN
./scripts/run-local.sh
```

Check local PostgreSQL 17 first when needed:

```bash
brew services list
pg_isready -h 127.0.0.1 -p 5432
brew services start postgresql@17

psql -h 127.0.0.1 -p 5432 -U mukadder -d research_archive
```

Useful read-only SQL inside `psql`:

```sql
\dn
\dt archive.*
SELECT count(*) FROM archive.award_version;
SELECT count(*) FROM archive.protocol_version;
SELECT count(*) FROM archive.negotiation;
```

Do not copy an RDS administrator password into the local Spring configuration. Use a separate local database role and local-only configuration.

### `run-award-loader.sh`: Award metadata loader

Always start with identity and help/static validation:

```bash
cd ~/projects/research-archive-platform
unset AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY AWS_SESSION_TOKEN
export AWS_PROFILE=bu-nprd
export AWS_REGION=us-east-1

aws sts get-caller-identity
test "$(aws sts get-caller-identity --query Account --output text)" = 770203350335
bash -n scripts/run-award-loader.sh
scripts/run-award-loader.sh --help
```

Recommended progression for a new loader image or schema revision:

```bash
# Changes RDS schema through a one-off ECS task.
scripts/run-award-loader.sh --migrate-only

# Creates a small ETL batch in the database.
scripts/run-award-loader.sh --create-batch 5

# Replace 7 with the actual ID printed above.
scripts/run-award-loader.sh --show-batch 7

# Reads Oracle and exercises transformations without committing the Award load.
scripts/run-award-loader.sh --load-batch 7 --dry-run

# Real database load. Run only after dry-run review.
scripts/run-award-loader.sh --load-batch 7
```

Idempotency check:

```bash
scripts/run-award-loader.sh --load-batch 7
```

The second real run should report zero inserted/updated rows and populated sections as unchanged. Scale gradually: 5, then 100, 1,000, and only then 5,000. Do not rerun a batch merely because the local waiter timed out. Inspect the ECS task first.

### `run-award-attachment-loader.sh`: attachment workflow

The attachment loader is intentionally separate from Award metadata. It handles Oracle BLOB reads, S3 object writes, checksums, upload status and retries.

```bash
cd ~/projects/research-archive-platform
unset AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY AWS_SESSION_TOKEN
export AWS_PROFILE=bu-nprd
export AWS_REGION=us-east-1

aws sts get-caller-identity
test "$(aws sts get-caller-identity --query Account --output text)" = "770203350335" \
  || { echo "Wrong AWS account; stop"; exit 1; }
bash -n scripts/run-award-attachment-loader.sh
scripts/run-award-attachment-loader.sh --help
```

Historical required non-secret deployment values included:

```bash
export ECR_REPOSITORY_URI='770203350335.dkr.ecr.us-east-1.amazonaws.com/research-archive-platform-dev-loader'
export CLUSTER_NAME='research-archive-platform-dev-etl'
export TASK_FAMILY='research-archive-platform-dev-loader'
export SUBNET_IDS='subnet-00fba12ee73ff0e3b,subnet-0c5b92d15314b93ed'
export SECURITY_GROUP_ID='sg-0817befcc5b4affc9'
export POSTGRES_HOST='research-archive-platform-dev-postgres.clb9d4mkglfd.us-east-1.rds.amazonaws.com'
export POSTGRES_PORT='5432'
export POSTGRES_DB='research_archive'
export AWARD_ATTACHMENT_BUCKET_NAME='research-archive-platform-dev-documents-770203350335'
```

Secret identifiers must be discovered from Terraform output, the ECS task definition, or Secrets Manager metadata. Do not paste literal secret values. The current script may use `POSTGRES_SECRET_ID` and `ORACLE_SECRET_ID`; confirm with `--help` and source inspection because earlier revisions used `_ARN` names.

Safe discovery:

```bash
aws secretsmanager list-secrets \
  --query 'SecretList[?contains(Name, `research-archive-platform/dev/`)].{Name:Name,ARN:ARN,Changed:LastChangedDate}' \
  --output table

aws ecs describe-task-definition \
  --task-definition research-archive-platform-dev-loader \
  --query 'taskDefinition.containerDefinitions[0].{Image:image,Environment:environment,Secrets:secrets}' \
  --output json
```

Migration-only execution changes ECS/ECR and RDS, but should not query Oracle or write S3 objects:

```bash
./scripts/run-award-attachment-loader.sh --migrate-only
```

Only after migration and startup validation succeeds should you perform a targeted dry-run or upload. Use the current script's `--help`; do not guess flags from old history.

Before any real attachment run, confirm all of the following:

- The Oracle secret points to the approved Kuali source and contains no value printed to the terminal.
- The PostgreSQL secret points to the BU dev archive database.
- The document bucket is the BU dev bucket in account `770203350335`.
- The selected subnets and security group allow Oracle, RDS, DNS, HTTPS and S3 access as designed.
- A targeted or dry-run mode has succeeded before a broad migration.
- No earlier ECS task is still running after a local waiter timeout.

The metadata loader and attachment loader are separate workflows:

| Loader | Source and destination | Primary result |
|---|---|---|
| `run-award-loader.sh` | Oracle relational Award data to PostgreSQL | Award families, versions and related metadata |
| `run-award-attachment-loader.sh` | Oracle attachment/BLOB data to S3 plus PostgreSQL tracking | Attachment objects, checksums, status and retry information |

### `run-archive-explorer.sh`: controlled archive investigation

The explorer is read-only at the database level, but its wrapper registers a task-definition revision and starts a Fargate task. Reuse an existing loader image to avoid an unnecessary Docker build and ECR push.

```bash
export SUBNET_IDS='replace-subnet-1,replace-subnet-2'
export SECURITY_GROUP_ID='replace-security-group-id'
export POSTGRES_SECRET_ID='research-archive-platform/dev/postgres'
export LOADER_IMAGE_URI='770203350335.dkr.ecr.us-east-1.amazonaws.com/research-archive-platform-dev-loader:replace-tag'

./scripts/run-archive-explorer.sh \
  award \
  --award-number 100185-00001 \
  --image-uri "$LOADER_IMAGE_URI"

./scripts/run-archive-explorer.sh \
  workflow \
  --document-number 328797 \
  --image-uri "$LOADER_IMAGE_URI"
```

Use `./scripts/run-archive-explorer.sh --help` as the authority for current subcommands and flags.

### `start-db-tunnel.sh`: when not to use it

The historical command tried to forward local port `15432` through an SSM managed instance. The Research Archive Terraform created ECS, RDS and S3 but no dedicated EC2 bastion. Seeing unrelated SSM-managed BU instances does not authorize using them as a tunnel.

Before starting any tunnel, prove all three facts:

1. The instance belongs to this project or is an approved shared access host.
2. Its security group and route table can reach the project RDS endpoint on `5432`.
3. You are authorized to use it for port forwarding.

Inventory only:

```bash
aws ssm describe-instance-information \
  --query 'InstanceInformationList[].{Id:InstanceId,Name:ComputerName,Ping:PingStatus}' \
  --output table
```

If those facts are not established, do not run `scripts/start-db-tunnel.sh`. Use local PostgreSQL for development and ECS one-off loaders for private RDS migrations and ETL.

At commit `2e69577`, `api/scripts/dev.sh` appears to calculate the project root as `api`, then looks for `api/scripts/start-db-tunnel.sh` and attempts to enter `api/api`. Until corrected, run the root tunnel check directly:

```bash
./scripts/start-db-tunnel.sh --check-only
```

### API deployment script

The pasted history found a serious defect: an older API deploy script hard-coded personal account `589744711110`. The corrected script must derive the active account from STS and refuse any account other than BU non-production `770203350335` unless a separately documented environment explicitly allows it.

Validation sequence:

```bash
cd ~/projects/research-archive-platform
unset AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY AWS_SESSION_TOKEN
export AWS_PROFILE=bu-nprd
export AWS_REGION=us-east-1

aws sts get-caller-identity
rg -n '589744711110' ops scripts terraform .github docs PROJECT_MEMORY.md 2>/dev/null

DEPLOY_SCRIPT='ops/deploy-api.sh'
test -f "$DEPLOY_SCRIPT" || DEPLOY_SCRIPT='scripts/deploy-api.sh'

bash -n "$DEPLOY_SCRIPT"
"$DEPLOY_SCRIPT" --check-only
```

The check-only output must show:

- Account `770203350335`.
- Region `us-east-1`.
- Expected ECR repository.
- Expected ECS cluster and service.
- No build, push, task registration or service update.

Only an explicit deployment request authorizes running the script without `--check-only`.

### Full dev deployment boundary

`scripts/dev-deploy.sh` is broader than API deployment. It may run backend and frontend tests, build and push images, update ECS, push Git changes, trigger Amplify and test the deployed API. Run only its preflight unless a full deployment is explicitly intended:

```bash
./scripts/dev-deploy.sh --check-only
```

Also note that `ops/AWS_OPERATIONS.md` referenced `open-api.sh`, but that file was not present at commit `2e69577`. Do not rely on that command unless it has since been added.

## 26. ECS task and loader monitoring

Find running and stopped loader tasks:

```bash
export RAP_LOADER_CLUSTER='research-archive-platform-dev-etl'
export RAP_LOADER_LOG_GROUP='/ecs/research-archive-platform-dev-loader'

aws ecs list-tasks --cluster "$RAP_LOADER_CLUSTER" --desired-status RUNNING
aws ecs list-tasks --cluster "$RAP_LOADER_CLUSTER" --desired-status STOPPED --max-results 20
```

Inspect one task:

```bash
export RAP_TASK_ARN='replace-with-real-task-arn'

aws ecs describe-tasks \
  --cluster "$RAP_LOADER_CLUSTER" \
  --tasks "$RAP_TASK_ARN" \
  --query 'tasks[0].{lastStatus:lastStatus,desiredStatus:desiredStatus,stopCode:stopCode,stoppedReason:stoppedReason,containers:containers[*].{name:name,lastStatus:lastStatus,exitCode:exitCode,reason:reason}}' \
  --output json
```

Tail loader logs:

```bash
aws logs tail "$RAP_LOADER_LOG_GROUP" --since 20m --follow
```

For a specific task:

```bash
RAP_TASK_ID="${RAP_TASK_ARN##*/}"
aws logs tail "$RAP_LOADER_LOG_GROUP" \
  --log-stream-names "loader/loader/$RAP_TASK_ID" \
  --follow
```

Interpretation:

| Result | Meaning |
|---|---|
| `RUNNING` after a waiter timeout | The CLI stopped waiting; the task may still be healthy. Do not start a duplicate. |
| `STOPPED`, exit code `0` | Successful completion. |
| `STOPPED`, nonzero exit | Inspect container reason and its exact log stream. |
| `DPY-6005` timeout | Network path, routing, SG, NACL or DNS. Not an Oracle password diagnosis. |
| `ORA-01017` | Oracle username/password invalid. |
| S3 `HeadBucket` 403 | IAM bucket-level permission issue, commonly a `ListBucket` condition incompatible with `HeadBucket`. |

## 27. API, Cognito and HTTPS verification

Current historical endpoints:

```bash
export RAP_API_URL='https://api-dev.app-nprd.aws-cloud.bu.edu'
export RAP_UI_URL='https://main.d288p9gmoteftb.amplifyapp.com/'
```

ECS API health:

```bash
aws ecs describe-services \
  --cluster research-archive-platform-dev-api \
  --services research-archive-platform-dev-api \
  --region us-east-1 \
  --query 'services[0].{running:runningCount,desired:desiredCount,pending:pendingCount,taskDefinition:taskDefinition,events:events[0:5].[createdAt,message]}' \
  --output json
```

You want `running=1`, `desired=1`, and `pending=0`.

```bash
curl -i "$RAP_API_URL/actuator/health"
curl -s "$RAP_API_URL/v3/api-docs" | jq -r '.paths | keys[] | select(contains("/api/v1/awards"))'
```

A secured Award endpoint should return `401` without a token. That confirms fail-closed security, not a broken route.

Verify Cognito from current Terraform outputs instead of relying on the two conflicting historical pool IDs:

```bash
cd ~/projects/research-archive-platform/terraform/environments/dev
terraform output -raw cognito_user_pool_id
terraform output -raw cognito_client_id

RAP_POOL_ID="$(terraform output -raw cognito_user_pool_id)"
RAP_CLIENT_ID="$(terraform output -raw cognito_client_id)"

aws cognito-idp describe-user-pool --user-pool-id "$RAP_POOL_ID" --region us-east-1
aws cognito-idp describe-user-pool-client \
  --user-pool-id "$RAP_POOL_ID" \
  --client-id "$RAP_CLIENT_ID" \
  --region us-east-1
```

Do not run `admin-set-user-password` with a password in shell history. Do not disable JWT validation to simplify testing.

## 28. Amplify UI build checks

These commands change the deployed UI when `start-job` is used.

Read-only inventory:

```bash
aws amplify get-app --app-id d288p9gmoteftb --region us-east-1
aws amplify list-branches --app-id d288p9gmoteftb --region us-east-1
aws amplify list-jobs \
  --app-id d288p9gmoteftb \
  --branch-name main \
  --max-results 10 \
  --region us-east-1
```

Manual release build, only when explicitly intended:

```bash
aws sts get-caller-identity
aws amplify start-job \
  --app-id d288p9gmoteftb \
  --branch-name main \
  --job-type RELEASE \
  --region us-east-1
```

After changing `VITE_API_BASE_URL`, Cognito callback URLs, or logout URLs, Amplify must rebuild because Vite embeds environment values into the browser bundle at build time.

The deployed UI is HTTPS. It must call an HTTPS API. An HTTPS page calling the old HTTP ALB endpoint will be blocked as mixed content.

## 29. Security cleanup required from pasted history

The pasted text contains or references credentials that should not remain valid:

1. A plaintext RDS administrator password was included. Rotate the PostgreSQL secret and confirm ECS/API task definitions consume the managed secret rather than a copied value.
2. A GitHub classic personal access token was reportedly exposed during Amplify setup. Revoke it in GitHub and replace it only if the integration still requires one.
3. Cognito passwords must not be placed directly in terminal commands, pasted files, chat, or shell history.
4. Never commit the pasted text files. They are troubleshooting history, not safe repository documentation.

After rotation, verify metadata without retrieving secret values:

```bash
aws secretsmanager describe-secret \
  --secret-id 'research-archive-platform/dev/postgres' \
  --query '{Name:Name,ARN:ARN,LastChangedDate:LastChangedDate,LastRotatedDate:LastRotatedDate,RotationEnabled:RotationEnabled}' \
  --output json
```

The exact secret name may have a generated suffix. Discover it with `list-secrets`; do not run `get-secret-value` merely to troubleshoot infrastructure.

## 30. Recommended troubleshooting order

1. Confirm `AWS_PROFILE`, region, caller account, and credential validity.
2. Confirm the affected resource exists and is healthy.
3. Resolve the actual source and destination IPs/ENIs.
4. Check the source subnet's effective route table.
5. Check the return route on the destination side.
6. Check source egress and destination ingress security-group rules.
7. Check both subnet NACLs, including ephemeral return ports.
8. Check DNS resolution from the actual source environment.
9. Test TCP from the actual source environment, preferably the ECS task.
10. Inspect VPC Flow Logs, ECS events, application logs, and RDS events.
11. Use CloudTrail/AWS Config to identify recent changes.
12. Use Reachability Analyzer only when the normal evidence is insufficient.

For an Oracle/Kuali connection failure, focus first on the exact Oracle destination IP. Only `10.58.33.0/25` and `10.58.33.128/25` are shown as peered. An address elsewhere in `10.58.32.0/22` will not follow those two routes. Then verify the Oracle-side return route to `10.30.0.0/16`, Oracle security rules, both NACLs, and whether the application is using the expected hostname/IP.

## 31. Terraform documentation review: bootstrap backend and Amplify repository drift (2026-08-04)

### What we were trying to accomplish

An operator pasted a third-party review of `terraform/README.md` and the modules it documents, claiming (among other things) two critical defects: that the bootstrap instructions cannot work from a fresh AWS account, and that the Amplify module's `lifecycle.ignore_changes` does not match what the README says it does. The task was to independently verify each claim against the actual repository content (not just trust the review), then fix whichever critical issues were confirmed.

### Symptoms and exact error messages

No live error was reproduced - no `terraform` binary was available in this session's sandbox (`which terraform` found nothing; `terraform version` did not run), and network access to `https://apt.releases.hashicorp.com/gpg` returned `curl: (22) The requested URL returned error: 403`, with `apt-get update` also failing (`E: Could not open lock file /var/lib/apt/lists/lock - open (13: Permission denied)`). All verification was therefore done by reading source files and git metadata directly, not by running `terraform init`/`plan`/`fmt` against the repo.

### Environment involved

Local sandbox working copy of the `research-archive-platform` repository (no AWS account, no live Terraform state, no Oracle/PostgreSQL/ECS/Cognito access). This was a static documentation-and-source-code review, not a live infrastructure incident.

### Diagnostic commands used

```bash
git ls-files terraform/bootstrap/
git log --oneline -3 -- terraform/bootstrap/backend.tf
cat -n terraform/bootstrap/backend.tf
cat terraform/bootstrap/backend.tf.example

grep -n "lifecycle\|ignore_changes\|repository\|access_token\|oauth_token" \
  terraform/modules/amplify/main.tf

git ls-files terraform/environments/dev/ | grep -i tfvars
git status --short terraform/environments/dev/

grep -rn "770203350335\|expected_account_id\|allowed_account_ids" \
  terraform/environments/*/main.tf terraform/environments/*/terraform.tfvars* \
  terraform/environments/*/variables.tf terraform/README.md

grep -rn "manage_cognito" terraform/
grep -rn "use_lockfile" terraform/
grep -n "provider\.tf\b" terraform/README.md; find terraform -name "provider*.tf"
grep -n -i "tflint\|checkov\|tfsec\|terraform validate\|terraform fmt" terraform/README.md
grep -n -B2 -A5 "state rm" terraform/README.md
grep -n -i "docker\|ECR\|:latest\|image tag" terraform/README.md
```

Plus targeted `sed -n '<range>p' terraform/README.md` reads of the bootstrap section (lines 1-70), the environment-configuration section (95-145), the Cognito/Amplify-ownership section (195-215), and the S3/Cognito `prevent_destroy` section (320-340).

### What each command proved

- `git ls-files terraform/bootstrap/` listed `backend.tf` as tracked, and `git log` showed it was committed in `50ecb68 feat(terraform): bootstrap remote state for BU AWS account`. `cat -n` showed it is a *complete* S3 backend block (not a partial one needing `-backend-config`) hardcoding `bucket = "research-archive-platform-tfstate-770203350335"`. Terraform auto-loads any backend block present in the working directory, so a fresh clone's `terraform init` in `terraform/bootstrap/` would use that real bucket immediately - it can never reach the "start on local state" step the README's own step 2 describes, because the file that step assumes doesn't exist yet is already committed.
- The `ignore_changes` grep showed `terraform/modules/amplify/main.tf` (before the fix) contained `ignore_changes = [access_token, oauth_token]`, with a comment directly above stating "repository is tracked (not ignored)". `terraform/README.md` (lines 266-268, read separately) claimed the ignored set was `[repository, access_token, oauth_token]`. The code and the doc's own quoted list did not match.
- `git ls-files terraform/environments/dev/ | grep -i tfvars` returned only `terraform.tfvars.example`, confirming `terraform.tfvars` itself is untracked (it matches the blanket `terraform.tfvars` line in `.gitignore`), contradicting the README's claim that dev's real file "is already checked into the repository."
- The `manage_cognito` grep, combined with the `sed` read of lines 195-215, showed the README states `false` is "dev's current setting" while `environments/dev/terraform.tfvars.example` (line 116) and the real local `environments/dev/terraform.tfvars` (line 68) both set `manage_cognito = true`.
- The `770203350335`/`expected_account_id` grep showed `environments/prod/terraform.tfvars.example:8` hardcodes the same account ID as dev's real account, while `environments/test/terraform.tfvars.example` correctly uses a `REPLACE_WITH_YOUR_12_DIGIT_ACCOUNT_ID` placeholder.
- The `use_lockfile` grep showed the setting present in `environments/{dev,test,prod}/backend.tf` but absent from `terraform/bootstrap/backend.tf`, with no note in the README explaining the difference.
- The `provider.tf` grep against actual filenames showed the README (line 185) refers to `provider.tf`, but the real files are `providers.tf` (plural) in all three environments.
- The `state rm` grep (lines 331-334) showed the README offers `terraform state rm` as a way to "destroy" `prevent_destroy`-protected resources, with no mention that this only removes the resource from Terraform's tracking - it stays alive, orphaned, in AWS.
- The `docker`/`ECR`/image grep across the full 390-line README returned no step describing building or pushing container images before the first `terraform apply` of a fresh environment, even though ECS is provisioned in the same configuration and references image tags that won't exist yet.

### Approaches that failed and why

Attempted to install `terraform` to run `terraform fmt -check -recursive` and independently confirm one of the review's High-severity claims (formatting drift in `environments/dev/main.tf`). Both installation paths failed in this sandbox: HashiCorp's apt repository returned HTTP 403 on the GPG key fetch, and the sandbox's own `apt-get` is permission-locked (`Could not open lock file /var/lib/apt/lists/lock`). This claim was left unverified rather than guessed at - see Remaining risks below.

### Confirmed root cause

Two independent, unrelated defects, both a mismatch between documentation and the actual repository state:

1. `terraform/bootstrap/backend.tf` was committed to git as a complete backend block with BU's real state bucket hardcoded in it, instead of being generated locally from `backend.tf.example` as the README's own instructions assume. This breaks the documented fresh-account bootstrap flow and weakens account portability.
2. `terraform/modules/amplify/main.tf`'s `lifecycle.ignore_changes` omitted `repository`, so the README's own "Recommended" flow (leave `repository_url` null, connect the repo manually via the AWS Console after the first apply) was undocumented-unsafe: a later `terraform apply` would see the manually-attached repository as drift and revert/detach it, since `repository` has no `ForceNew` and was being actively reconciled.

### Exact resolution

1. Added `terraform/bootstrap/backend.tf` to `.gitignore` (with an explanatory comment distinguishing it from `environments/*/backend.tf`, which are intentionally generic and safe to commit), then ran `git rm --cached terraform/bootstrap/backend.tf` to untrack it while leaving the working-copy file in place, so BU's existing, already-bootstrapped setup keeps working locally.
2. Changed `ignore_changes = [access_token, oauth_token]` to `ignore_changes = [repository, access_token, oauth_token]` in `terraform/modules/amplify/main.tf`, and rewrote the surrounding comment to explain why `repository` is now ignored (matches the README's published Recommended flow) and to note that the legacy PAT-based flow (`repository_url` + `github_access_token` set from the start) still works for *initial* creation - `ignore_changes` only suppresses drift detection on later applies, not the values used when the resource is first created.

Neither change touches live AWS resources or state; both are source/config edits only. Nothing was committed or pushed.

### Validation commands and results

```bash
git status --short terraform/ .gitignore
#  M .gitignore
# D  terraform/bootstrap/backend.tf
#  M terraform/modules/amplify/main.tf

git diff .gitignore
git diff terraform/modules/amplify/main.tf
git diff --cached terraform/bootstrap/backend.tf

test -f terraform/bootstrap/backend.tf && echo "yes, still present locally"
# yes, still present locally
```

Confirmed: the working-copy `backend.tf` still exists on disk (so BU's own bootstrap continues to function), git no longer tracks it, the new `.gitignore` rule matches it (`git check-ignore -v` against it and files under `bu/` both resolved correctly earlier in the session), and the Amplify module diff is limited to the `ignore_changes` list and its comment - no resource arguments changed.

### Prevention and faster diagnosis next time

- Before trusting a README's description of a `lifecycle` block, `grep` the actual resource block - a stale comment or doc update that didn't keep pace with a later code change is cheap to miss otherwise.
- For any `terraform/**/backend.tf`, check `git ls-files` before assuming a "starts on local state" narrative is actually possible from a fresh clone - a backend block committed with a real bucket name silently defeats it.
- When a doc claims a file "is checked into the repository," verify with `git ls-files <path>`, not by reading the file's presence on disk (a locally-generated, gitignored copy looks identical to a tracked one until you check).
- Keep a `terraform` binary (or `tfenv`) available in any environment expected to validate Terraform docs/config end-to-end; this session could not run `fmt`, `validate`, or `plan` at all, which left one review claim unverifiable.

### Remaining risks or unresolved work

Not fixed in this session - confirmed accurate by the same review but left open, tracked in the Obsidian vault (`08 Problems and Solutions/Open Problems.md`) and summarized here for repo-side visibility:

- **High**: `terraform/README.md` still claims dev's `terraform.tfvars` is checked into git (it isn't); still has no explicit "provision ECR, build/push images, then apply" sequence for a first deployment; `environments/prod/terraform.tfvars.example` still hardcodes the existing dev account ID (`770203350335`) instead of a placeholder, unlike `test`'s example.
- **High, unverified**: the claim that `terraform fmt -check -recursive terraform` currently reports drift in `environments/dev/main.tf` - could not be run in this environment; needs confirming with a real `terraform` install.
- **Medium**: the README's Cognito section still says `manage_cognito = false` is "dev's current setting" when dev's actual config sets it `true`; `environments/test|prod/variables.tf` still describe `amplify_repository_url`/`amplify_github_access_token` as "Required when manage_amplify = true," contradicting the README's Recommended (leave-both-null) flow; `terraform/bootstrap/backend.tf` still lacks `use_lockfile = true` with no note explaining why it differs from every environment's backend; the README's `terraform state rm` guidance still doesn't clarify that it orphans rather than deletes the live resource; no operator prerequisites checklist beyond "admin/power-user role"; post-apply verification guidance still stops at `terraform output` rather than checking ECS/ALB health, DNS/HTTPS, RDS connectivity, Cognito login, or Amplify build status.
- **Low**: README still says `provider.tf` where the real files are `providers.tf`; the `tflint`/`checkov`/`tfsec` mention still has no install/config/CI guidance; no explicit warning that backend/account selection happens before `allowed_account_ids` can protect anything.
- None of the above required a source-code change to fix (they're all `terraform/README.md` prose corrections) - they were left for a follow-up pass rather than bundled into this one, since only the two Critical, code-level issues had been explicitly approved for fixing in this session.

## 32. Proposal Explorer 404 investigation and dev API task-definition/Terraform-state divergence (2026-08-10)

### Symptom

`GET /api/v1/explorer/proposals` on the dev Amplify UI returned `403 Forbidden` with `WWW-Authenticate: Bearer error="insufficient_scope"`, while every other authenticated tab worked normally.

### Investigation summary (see chat history for the full step-by-step trace)

The `insufficient_scope` response was a red herring produced by `BearerTokenAccessDeniedHandler` - RFC 6750 has no generic "access denied" error code, so Spring reuses `insufficient_scope` for any post-authentication `AccessDeniedException`, regardless of cause. A live Spring Security TRACE capture (temporary `LOGGING_LEVEL_ORG_SPRINGFRAMEWORK_SECURITY=TRACE` env var on a throwaway task-definition revision, reverted immediately after capture) proved the real sequence: `/api/v1/explorer/proposals` passed Spring Security's `.authenticated()` check cleanly, then Spring Boot's own "no handler found" path internally forwarded the request to `GET /error`, which - because `/error` matched neither the `permitAll` list nor `/api/**` - fell through to `.anyRequest().denyAll()` and got denied, producing the misleading 403.

Fix #1 (kept permanently, per explicit instruction): added `/error` to `SecurityConfiguration`'s `permitAll()` matcher list (commit `ad41d17`) so Boot's own error view is never masked by the security fallback again. This unmasked the true status: `404`, with a genuine `BasicErrorController` JSON body (`{"status":404,"error":"Not Found","path":"/api/v1/explorer/proposals"}`).

A second temporary diagnostic filter (`TemporaryRequestLoggingConfiguration`, registered at `Ordered.HIGHEST_PRECEDENCE`, logging every request's method/URI/query string before Spring Security even ran) proved the request *was* reaching the JVM - ruling out ALB/CloudFront/routing causes. (Added in commit `adde318`, removed again in commit `47aceeb` once no longer needed.)

Real root cause: `ExplorerController` is gated by a class-level `@ConditionalOnProperty(name = "app.explorer.enabled", havingValue = "true")`, bound to `${APP_EXPLORER_ENABLED:false}` in `application.yml` - defaulting to disabled. The dev Amplify environment sets `VITE_EXPLORER_ENABLED = "true"` (so the UI correctly shows the Explorer nav/route), but the dev API's ECS task definition never had a matching `APP_EXPLORER_ENABLED`, and `terraform/environments/dev/terraform.tfvars`'s `additional_api_environment_variables` never set it either - a real gap between the two independently-declared flags, not a code bug. Since Spring Security's `.requestMatchers("/api/**").authenticated()` matches on path pattern alone, independent of whether any controller handles it, unauthenticated probes returned a plausible-looking `401` that incorrectly suggested the route existed.

### Fix applied

1. `terraform/environments/dev/main.tf`: added a `terraform_data.explorer_flags_match` resource with a `lifecycle.precondition` that hard-fails `plan`/`apply` if `local.explorer_enabled_ui` (now the single source powering `VITE_EXPLORER_ENABLED`) is true while `additional_api_environment_variables["APP_EXPLORER_ENABLED"]` isn't also `"true"` (commit `47aceeb`). This is the permanent guard against this exact class of drift recurring.
2. `terraform/environments/dev/terraform.tfvars` (gitignored, not committed - matches the existing pattern for `APP_SEARCH_SEMANTIC_ENABLED`): added `APP_EXPLORER_ENABLED = "true"` to `additional_api_environment_variables`, and pinned `api_image_tag` to the exact tag already running (`20260810T161549Z-47aceeb`) instead of leaving it at the `api_image_tag` variable's mutable `"latest"` default.
3. **Applied via a direct ECS task-definition update, not `terraform apply`** - see divergence note below for why.

### Terraform-state divergence - intentional, recorded here per explicit instruction

Running `terraform plan` (and even a `-target`-scoped one) for the `APP_EXPLORER_ENABLED` change also proposed changing `module.amplify[0].aws_amplify_app.ui` (`+ auto_branch_creation_config { enable_auto_build = false }`) - unrelated, pre-existing drift between Terraform source and the live Amplify app, first observed as an *additional* surprise beyond the two already known-and-documented issues in Section 31. This couldn't be excluded from the plan: `aws_ecs_task_definition.api`'s `APP_CORS_ALLOWED_ORIGINS` value is computed from `local.cors_allowed_origins`, which reads `module.amplify[0].default_domain`, creating a real dependency edge. `-target` therefore pulled the entire `aws_amplify_app.ui` resource (and its independent pending diff) into the apply set. This Terraform version (1.15.7) has no `-exclude` flag to counter that.

Rather than either (a) applying the Amplify change as a side effect of an unrelated fix, or (b) leaving Explorer broken while the Amplify drift got investigated, the API-only fix was applied directly via `aws ecs register-task-definition` + `aws ecs update-service` (task-definition revision 42, cluster/service `research-archive-platform-dev-api`), copying every existing environment variable from the then-current revision 41 unchanged and adding only `APP_EXPLORER_ENABLED=true`. **Terraform state still reflects an older task-definition revision** (it was already stale before this incident, per Section 31's related finding that `ops/deploy-api.sh` deploys out-of-band and never updates Terraform state) and now additionally lacks awareness of `APP_EXPLORER_ENABLED` on the live resource, even though it's present in `terraform.tfvars` and would be applied correctly *if* the Amplify drift blocking a clean apply is resolved first.

### Follow-up task (explicitly not bundled into this fix)

1. Investigate why `module.amplify[0].aws_amplify_app.ui`'s live state lacks `auto_branch_creation_config` matching what `terraform/modules/amplify/main.tf` now declares - was this ever applied, or is it new drift from a module change made without a corresponding `apply`?
2. Decide whether `auto_branch_creation_config { enable_auto_build = false }` is actually the intended live setting (it looks like a safe, low-risk default, but hasn't been deliberately confirmed).
3. Once resolved, run `terraform plan` for the dev environment and confirm it now shows **only** a clean no-op or expected reconciliation for `aws_ecs_task_definition.api`/`aws_ecs_service.api` (matching the already-live revision 42 configuration), then `terraform apply` to bring state back in sync with reality.
4. Re-verify the `terraform_data.explorer_flags_match` precondition still passes after reconciliation.

### Validation

- `/actuator/health` → `{"status":"UP","groups":["liveness","readiness"]}` (`200`) after the task-definition-42 rollout stabilized.
- Authenticated browser retry of `GET /api/v1/explorer/proposals` confirmed working with real data returned.
- `ExplorerController.class` confirmed present in both the local Maven build output and the actual deployed jar (`docker cp` + `unzip -l`) - ruled out a packaging/build-context problem before looking at configuration.

### Prevention

The `terraform_data.explorer_flags_match` precondition (Fix #1 above) is the durable guard: any future `terraform plan`/`apply` will hard-fail with a clear error message if `VITE_EXPLORER_ENABLED` and `APP_EXPLORER_ENABLED` ever diverge again, rather than silently shipping a UI nav item/route with no working backend behind it.
