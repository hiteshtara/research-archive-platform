# Shibboleth Authentication Integration Plan

## Overview

This document evaluates three approaches for replacing AWS Cognito authentication with BU Shibboleth authentication in the Research Archive Platform, ordered from **best to worst**. All options leverage existing `shibboleth-sp` and `shibboleth-sp-at-edge` implementations to handle SAML flows with BU's Identity Provider.

### Current State

- **Authentication**: AWS Cognito User Pool with OAuth2/JWT
- **Authorization**: Cognito groups mapped to Spring Security `ROLE_*` authorities
  - `cognito:groups` claim → `ROLE_ArchiveAttachmentViewer`, etc.
- **UI**: Amplify-hosted React app using `aws-amplify` library for auth
- **API**: Spring Boot with `SecurityConfiguration` validating Cognito JWTs
- **Infrastructure**: ECS Fargate behind ALB (API) + Amplify (UI)

### Goal

Replace Cognito authentication with BU Shibboleth while maintaining or improving:
- Current authorization model (group-based access control)
- User experience (seamless login/logout)
- Security posture
- Performance

### Key Insight

All three options share a common pattern: Shibboleth authenticates users and issues JWTs with **mapped attributes**. The difference is **where** this happens and **what infrastructure** is required.

---

## Option 1: CloudFront Distribution + Lambda@Edge ⭐⭐⭐⭐⭐ 

###🥇 BEST OPTION - Your code is 90% complete!

### Architecture

```
User → CloudFront Distribution
     ↓
Viewer Request Lambda@Edge (runs on EVERY request)
  - Fast JWT validation (signature + expiration)
  - Valid JWT → proceed to cache/origin
  - Invalid/missing JWT → set cache-bypass header
     ↓
[CloudFront Cache: serves authenticated users from edge]
     ↓
Origin Request Lambda@Edge (runs only on cache miss or auth needed)
  - Handles SAML flow with Shibboleth IdP
  - Receives SAML assertion with attributes
  - Maps entitlements → JWT groups claim:
      'urn:mace:bu.edu:group:ArchiveViewer' → {"groups": ["ArchiveAttachmentViewer"]}
  - Issues signed JWT (httpOnly cookie)
  - Redirects back to UI
     ↓
Origins:
  ├─ Amplify UI (static content)
  └─ ALB → Spring Boot API (validates JWT)
```

### Description

Create a CloudFront distribution in front of both the Amplify UI and API ALB, with Lambda@Edge functions handling authentication at the edge. Your existing `shibboleth-sp-at-edge` CDK stack provides 90% of the implementation—just add attribute mapping logic.

### Pros

✅ **Your code is 90% complete**: `shibboleth-sp-at-edge` already exists and works  
✅ **Eliminates Cognito entirely**: No AWS Cognito dependency or costs  
✅ **Best performance**: Authenticated requests cached at 300+ CloudFront edge locations  
✅ **Lowest latency**: Authentication happens at regional edge, closest to users  
✅ **Protects both UI and API**: Single security boundary for entire application  
✅ **Stateless JWT-based**: No session store required  
✅ **Global scale**: CloudFront's edge network (vs. single-region ECS)  
✅ **Pay-per-invocation**: Serverless cost model (no always-on containers)  
✅ **Full attribute mapping control**: Map any Shibboleth attribute to any JWT claim  
✅ **Intelligent caching**: Authenticated requests cached, unauthenticated bypass cache  
✅ **No container overhead**: Spring Boot task stays single-container  
✅ **Battle-tested pattern**: Lambda@Edge + CloudFront is proven at scale  

### Cons

❌ **Infrastructure topology**: Introduces CloudFront as new component (but operational simplicity is high)  
❌ **Lambda@Edge deploy time**: 15-30 minutes to replicate to all regions  
❌ **UI changes required**: Rewrite `ui/src/auth.ts` (50-100 lines)  
❌ **Spring Boot changes required**: Modify `SecurityConfiguration` to read `groups` claim  
❌ **Amplify + CloudFront coordination**: Amplify creates its own CF distribution; must override  
❌ **Lambda@Edge limitations**: 
  - Must deploy to us-east-1 only
  - 1MB body limit (sufficient for SAML, but a constraint)
  - Cold start latency on first request to new edge location  
❌ **JWT key management**: Must securely distribute keypair (Secrets Manager)  
❌ **Debugging complexity**: Logs spread across multiple regions  

### Implementation Steps

1. **Add attribute mapping to Origin Request Lambda**
   
   Modify `shibboleth-sp-at-edge/lib/lambda/FunctionSpOriginRequest.ts`:
   
   ```typescript
   // After SAML assertion is processed and attributes extracted
   const samlAttributes = extractedFromSAML; 
   // e.g., { entitlement: [...], eppn: "user@bu.edu", mail: "user@bu.edu" }
   
   // Map Shibboleth attributes to Spring Boot groups
   const groups = mapShibbolethToGroups(samlAttributes);
   
   // Build JWT payload with mapped groups
   const payload = {
     sub: samlAttributes.eppn || samlAttributes.name_id,
     email: samlAttributes.mail,
     groups: groups,  // <-- Spring Boot reads this
     shibboleth: samlAttributes  // Optional: preserve raw for debugging
   };
   
   // Issue JWT (library already does this)
   jwtTools.setCookieInResponse(response, payload);
   
   // Mapping function
   function mapShibbolethToGroups(samlAttributes: any): string[] {
     const mapping: Record<string, string> = {
       'urn:mace:bu.edu:group:ArchiveViewer': 'ArchiveAttachmentViewer',
       'urn:mace:bu.edu:group:ArchiveAdmin': 'ArchiveAdmin',
       // Add more mappings as needed
     };
     
     const entitlements = samlAttributes.entitlement || [];
     return entitlements
       .map((e: string) => mapping[e])
       .filter(Boolean);
   }
   ```

2. **Modify Spring Boot SecurityConfiguration**
   
   ```java
   @Bean
   Converter<Jwt, ? extends AbstractAuthenticationToken> jwtAuthenticationConverter() {
       JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
       
       converter.setJwtGrantedAuthoritiesConverter(jwt -> {
           var authorities = new ArrayList<GrantedAuthority>();
           
           // Read from "groups" claim (not "cognito:groups")
           List<String> groups = jwt.getClaimAsStringList("groups");
           
           if (groups != null) {
               groups.stream()
                   .map(group -> "ROLE_" + group)
                   .map(SimpleGrantedAuthority::new)
                   .forEach(authorities::add);
           }
           
           return authorities;
       });
       
       return converter;
   }
   
   @Bean
   NimbusJwtDecoder jwtDecoder(@Value("${app.security.jwt.public-key}") String publicKeyPEM) {
       return NimbusJwtDecoder.withPublicKey(loadPublicKey(publicKeyPEM)).build();
   }
   ```

3. **Generate and store JWT keypair**
   
   ```bash
   # Generate keypair
   openssl genrsa -out private.pem 2048
   openssl rsa -in private.pem -pubout -out public.pem
   
   # Store in Secrets Manager
   aws secretsmanager create-secret \
     --name /research-archive/dev/jwt-keypair \
     --secret-string '{"private":"...","public":"..."}'
   ```

4. **Configure CloudFront distribution**
   
   Update `shibboleth-sp-at-edge/context/context.json`:
   
   ```json
   {
     "STACK_ID": "research-archive-platform-dev",
     "SHIBBOLETH": {
       "entityId": "https://archive.bu.edu/shibboleth",
       "entryPoint": "https://shib.bu.edu/idp/profile/SAML2/Redirect/SSO",
       "logoutUrl": "https://shib.bu.edu/idp/logout.jsp",
       "idpCert": "...",
       "secret": {
         "secretArn": "arn:aws:secretsmanager:us-east-1:...",
         "samlCertSecretFld": "SAML_CERT",
         "samlPkSecretFld": "SAML_PK"
       }
     },
     "ORIGINS": [
       {
         "type": "amplify",
         "domain": "main.d1234567890.amplifyapp.com"
       },
       {
         "type": "alb",
         "domain": "research-archive-platform-dev-alb-1234567890.us-east-1.elb.amazonaws.com",
         "path": "/api/*"
       }
     ]
   }
   ```

5. **Deploy CloudFront + Lambda@Edge**
   
   ```bash
   cd shibboleth-sp-at-edge
   npm install
   cdk deploy
   ```

6. **Rewrite UI auth module**
   
   ```typescript
   // ui/src/auth.ts
   // JWT is in httpOnly cookie set by Lambda@Edge
   
   export async function login(): Promise<void> {
     // Redirect triggers Lambda@Edge SAML flow
     window.location.href = '/shibboleth/login';
   }
   
   export async function logout(): Promise<void> {
     window.location.href = '/shibboleth/logout';
   }
   
   export async function accessToken(): Promise<string | null> {
     // Token is httpOnly cookie - frontend can't read directly
     // Verify authentication via test API call
     try {
       const response = await fetch('/api/dashboard');
       return response.ok ? 'authenticated' : null;
     } catch {
       return null;
     }
   }
   
   export async function hasAttachmentAccess(): Promise<boolean> {
     // Call lightweight API endpoint that checks groups server-side
     try {
       const response = await fetch('/api/auth/check-attachment-access');
       return response.ok;
     } catch {
       return false;
     }
   }
   ```

7. **Update API to add helper endpoint**
   
   ```java
   @RestController
   @RequestMapping("/api/auth")
   public class AuthCheckController {
       
       private final AttachmentAuthorizationService authService;
       
       @GetMapping("/check-attachment-access")
       public ResponseEntity<Void> checkAttachmentAccess(Authentication authentication) {
           try {
               authService.requireAttachmentAccess(authentication);
               return ResponseEntity.ok().build();
           } catch (AttachmentAccessDeniedException e) {
               return ResponseEntity.status(403).build();
           }
       }
   }
   ```

8. **Register with BU Shibboleth IdP**
   - Provide SP metadata (generated by Lambda@Edge)
   - Configure ACS URL: `https://<cloudfront-domain>/saml/acs`

9. **Update Terraform to point UI/API URLs to CloudFront**
   - Update Route53 records (if using custom domain)
   - Update Amplify environment variables

### Estimated Timeline

- **Week 1**: Add attribute mapping to Lambda@Edge, Spring Boot config changes
- **Week 2**: Deploy CloudFront + Lambda@Edge, JWT keypair setup
- **Week 3**: UI auth rewrite, helper endpoint, integration testing
- **Week 4**: BU IdP coordination, Terraform updates, production deploy

### Rating: ⭐⭐⭐⭐⭐ (Excellent)

**Best for**: Organizations that want the best architecture, have existing Lambda@Edge code, and can invest 2-3 weeks.

---

## Option 2: ECS Sidecar Container ⭐⭐⭐⭐

### 🥈 SECOND BEST - Eliminates Cognito but more complex than CloudFront

### Architecture

```
User → Amplify UI (unauthenticated)
     → Redirects to ALB
     → ALB routes to ECS Task:
        ├─ Sidecar Container (port 3000) - shibboleth-sp proxy
        │  ├─ Initiates SAML flow with Shibboleth IdP
        │  ├─ Receives SAML assertion with attributes
        │  ├─ Maps entitlements → JWT groups claim
        │  ├─ Issues signed JWT
        │  └─ Proxies authenticated requests to Spring Boot
        └─ Spring Boot Container (port 8080)
           └─ Validates JWT, enforces authorization
```

### Description

Add a second container to the ECS task definition running the `shibboleth-sp` library as an authentication proxy. The sidecar sits in front of Spring Boot, handles all SAML flows, maps Shibboleth attributes to JWT claims, and proxies authenticated requests to Spring Boot on `localhost`.

### Pros

✅ **Eliminates Cognito entirely**: No AWS Cognito dependency or costs  
✅ **Full attribute mapping control**: Map any Shibboleth attribute to any JWT claim in code  
✅ **Stateless JWT-based**: No session store required  
✅ **Scales with ECS**: Authentication capacity scales with application tasks  
✅ **ECS-native**: Leverages existing ECS infrastructure (no new AWS services)  
✅ **Network isolation**: Sidecar and app communicate over localhost  
✅ **Your code ready**: `shibboleth-sp` library exists and is containerizable  
✅ **Flexible**: Can customize authentication flow logic as needed  

### Cons

❌ **Container overhead**: Every ECS task runs 2 containers (2x memory/CPU)  
❌ **UI changes required**: Rewrite `ui/src/auth.ts` (50-100 lines)  
❌ **Spring Boot changes required**: Modify `SecurityConfiguration` to read `groups` claim instead of `cognito:groups`  
❌ **ALB routing to sidecar**: Target group points to port 3000, not 8080  
❌ **No UI protection at origin**: Amplify UI still accessed directly (not behind sidecar)  
❌ **Health check complexity**: Must verify both sidecar and Spring Boot health  
❌ **JWT key management**: Must securely distribute keypair (Secrets Manager)  
❌ **Deployment complexity**: More moving parts to coordinate  
❌ **Resource costs**: Always-on containers (vs. serverless pay-per-use)  

### Implementation Steps

1. **Containerize shibboleth-sp as proxy**
   - Create Dockerfile with `shibboleth-sp` + Express proxy
   - Add attribute → group mapping logic:
     ```typescript
     const mapping: Record<string, string> = {
       'urn:mace:bu.edu:group:ArchiveViewer': 'ArchiveAttachmentViewer',
       'urn:mace:bu.edu:group:ArchiveAdmin': 'ArchiveAdmin',
     };
     const groups = samlAttributes.entitlement
       .map(e => mapping[e])
       .filter(Boolean);
     ```
   - Configure JWT signing with RS256

2. **Generate and store JWT keypair**
   ```bash
   # Generate keypair
   openssl genrsa -out private.pem 2048
   openssl rsa -in private.pem -pubout -out public.pem
   
   # Store in Secrets Manager
   aws secretsmanager create-secret \
     --name /research-archive/dev/jwt-keypair \
     --secret-string '{"private":"...","public":"..."}'
   ```

3. **Update ECS task definition**
   ```json
   {
     "containerDefinitions": [
       {
         "name": "shibboleth-sidecar",
         "image": "research-archive-shibboleth-sp:latest",
         "portMappings": [{"containerPort": 3000}],
         "environment": [
           {"name": "ENTITY_ID", "value": "https://archive.bu.edu/shibboleth"},
           {"name": "ENTRY_POINT", "value": "https://shib.bu.edu/idp/profile/SAML2/Redirect/SSO"},
           {"name": "PROXY_TARGET", "value": "http://localhost:8080"}
         ],
         "secrets": [
           {"name": "JWT_PRIVATE_KEY", "valueFrom": "arn:aws:secretsmanager:..."},
           {"name": "SAML_PK", "valueFrom": "arn:aws:secretsmanager:..."}
         ]
       },
       {
         "name": "spring-boot-api",
         "image": "research-archive-api:latest",
         "portMappings": [{"containerPort": 8080}],
         "environment": [
           {"name": "SPRING_PROFILES_ACTIVE", "value": "dev"}
         ],
         "secrets": [
           {"name": "JWT_PUBLIC_KEY", "valueFrom": "arn:aws:secretsmanager:..."}
         ]
       }
     ]
   }
   ```

4. **Modify Spring Boot SecurityConfiguration**
   
   Same as Option 1 (read `groups` claim instead of `cognito:groups`).

5. **Rewrite UI auth module**
   ```typescript
   // ui/src/auth.ts
   const API_BASE = import.meta.env.VITE_API_URL;
   const UI_BASE = window.location.origin;
   
   export async function login(): Promise<void> {
     const loginUrl = `${API_BASE}/shibboleth/login?return_url=${encodeURIComponent(UI_BASE)}`;
     window.location.assign(loginUrl);
   }
   
   export async function logout(): Promise<void> {
     const logoutUrl = `${API_BASE}/shibboleth/logout?return_url=${encodeURIComponent(UI_BASE)}`;
     window.location.assign(logoutUrl);
   }
   
   export async function accessToken(): Promise<string | null> {
     return localStorage.getItem('auth_token');
   }
   ```

6. **Update ALB target group**
   - Point to sidecar port 3000 (not Spring Boot 8080)
   - Configure health check for sidecar endpoint

7. **Register with BU Shibboleth IdP**
   - Provide SP metadata (generated by sidecar)
   - Configure ACS URL: `https://api.archive.bu.edu/saml/acs`

### Estimated Timeline

- **Week 1**: Containerize shibboleth-sp, JWT keypair setup
- **Week 2**: Spring Boot config changes, ECS task definition
- **Week 3**: UI auth rewrite, integration testing
- **Week 4**: BU IdP coordination, production deploy

### Rating: ⭐⭐⭐⭐ (Very Good)

**Best for**: Organizations that want to eliminate Cognito, prefer ECS-native solutions, and don't want CloudFront.

---

## Option 3: Cognito SAML Federation ⭐⭐⭐

### 🥉 THIRD BEST - Fastest path but keeps Cognito

### Architecture

```
User → Cognito User Pool (SAML federation) → Shibboleth IdP
     ← SAML assertion with attributes ←
     → Cognito issues JWT with mapped groups →
UI/API validates Cognito JWT (unchanged)
```

### Description

Configure Cognito User Pool to federate with BU's Shibboleth IdP as a SAML Identity Provider. Cognito handles the SAML flow, maps Shibboleth attributes to Cognito groups, and issues JWTs exactly as it does today.

### Pros

✅ **Zero application code changes**: Spring Boot and UI remain 100% unchanged  
✅ **AWS-native SAML support**: Battle-tested, production-grade  
✅ **Lowest risk**: Purely configuration change  
✅ **Fastest implementation**: 1-2 weeks  
✅ **Preserves all authorization logic**: `AttachmentAuthorizationService`, role checks work as-is  
✅ **Amplify integration unchanged**: UI's `aws-amplify` library continues to work  
✅ **No infrastructure changes**: No new AWS services required  

### Cons

❌ **Keeps Cognito dependency**: Still tied to AWS Cognito (vendor lock-in)  
❌ **Cognito MAU costs**: Ongoing per-user charges  
❌ **Limited attribute mapping**: Constrained by Cognito's SAML attribute mapping capabilities  
❌ **Dual management**: Must maintain both Cognito User Pool and Shibboleth SP registration  
❌ **Group synchronization**: Shibboleth entitlements must map to pre-existing Cognito groups  
❌ **No direct control**: Cognito acts as intermediary; can't customize JWT claims beyond what Cognito allows  

### Implementation Steps

1. **Register Cognito as SP with BU Shibboleth**
   - Provide Cognito's SAML metadata URL
   - Configure ACS (Assertion Consumer Service) URL
   - Map BU entitlements to Cognito groups

2. **Configure Cognito User Pool**
   - Add Shibboleth as SAML Identity Provider
   - Configure attribute mapping:
     - `urn:mace:bu.edu:group:ArchiveViewer` → Cognito group `ArchiveAttachmentViewer`
   - Update Hosted UI to show "Sign in with BU" option

3. **Test authentication flow**
   - Verify SAML assertions received
   - Confirm group membership in JWT
   - Test attachment authorization

4. **Update documentation**
   - User onboarding instructions
   - Group management procedures

### Estimated Timeline

- **Week 1**: BU IdP coordination, Cognito configuration
- **Week 2**: Testing, documentation, production deploy

### Rating: ⭐⭐⭐ (Good)

**Best for**: Organizations that need the fastest path (1-2 weeks) and are comfortable keeping Cognito.

---

## Comparison Matrix

| Criterion | **Option 1: CloudFront** | **Option 2: Sidecar** | **Option 3: Federation** |
|-----------|--------------------------|------------------------|--------------------------|
| **Eliminates Cognito** | ✅ Yes | ✅ Yes | ❌ No |
| **Code changes required** | ⚠️ Medium | ⚠️ Medium | ✅ None |
| **Infrastructure topology** | ❌ Adds CloudFront | ⚠️ ECS-native | ✅ No change |
| **Your existing code ready** | ✅ 90% done | ⚠️ Partial | N/A |
| **Performance** | ✅ Best (edge cache) | ⚠️ Good | ⚠️ Good |
| **UI protection** | ✅ Yes | ❌ No | ✅ Yes |
| **Vendor lock-in** | ✅ None | ✅ None | ❌ AWS (Cognito) |
| **Authorization control** | ✅ Full | ✅ Full | ⚠️ Limited |
| **Resource costs** | ✅ Pay-per-invocation | ❌ Always-on ECS | ❌ Cognito MAUs |
| **Scalability** | ✅ CloudFront global | ⚠️ ECS task limits | ✅ Cognito scale |
| **Implementation time** | ⚠️ 2-3 weeks | ⚠️ 3-4 weeks | ✅ 1-2 weeks |
| **Risk level** | ⚠️ Medium | ⚠️ Medium | ✅ Lowest |
| **Deployment complexity** | ❌ Complex | ⚠️ Medium | ✅ Simple |
| **Debugging ease** | ❌ Complex (multi-region logs) | ⚠️ Medium | ✅ Simple |

---

## Overall Ratings

### 🥇 First Place: Option 1 - CloudFront + Lambda@Edge
**Rating: ⭐⭐⭐⭐⭐ (Excellent)**

**Best for**: Organizations that want the best architecture, have existing Lambda@Edge code, and can invest 2-3 weeks.

**Choose this if**:
- **You already have `shibboleth-sp-at-edge` working** ← Critical advantage
- You want to eliminate Cognito entirely
- Performance and scalability matter
- You want to protect both UI and API at the edge
- You're willing to invest 2-3 weeks
- You serve users across multiple regions

**Skip this if**:
- You need the absolute fastest path to production (choose Federation)
- You lack experience with CloudFront/Lambda@Edge
- Debugging across multiple regions concerns you
- You strongly prefer infrastructure you can see/touch (ECS containers)

---

### 🥈 Second Place: Option 2 - ECS Sidecar Container
**Rating: ⭐⭐⭐⭐ (Very Good)**

**Best for**: Organizations that want to eliminate Cognito, prefer ECS-native solutions, and don't want CloudFront.

**Choose this if**:
- You want to eliminate Cognito entirely
- You prefer infrastructure you already understand (ECS)
- You're willing to invest 3-4 weeks
- You don't want to add CloudFront to your architecture
- Your users are primarily in a single region

**Skip this if**:
- Performance at scale is critical (no edge caching)
- You want to minimize container overhead
- Global distribution matters
- Your `shibboleth-sp-at-edge` code is already working

---

### 🥉 Third Place: Option 3 - Cognito SAML Federation
**Rating: ⭐⭐⭐ (Good)**

**Best for**: Organizations that want the fastest, lowest-risk path to Shibboleth authentication and are comfortable keeping Cognito.

**Choose this if**:
- You need Shibboleth authentication in production in 1-2 weeks
- Zero code changes is a hard requirement
- You're okay with ongoing Cognito costs
- You don't need fine-grained control over attribute mapping

**Skip this if**:
- Your goal is to eliminate AWS vendor lock-in
- You need complex attribute → role mapping logic
- You want to minimize ongoing operational costs

---

## Recommendation

### **Primary Recommendation: Option 1 - CloudFront + Lambda@Edge**

**Why this is the winner:**

1. **Your code is 90% done**: `shibboleth-sp-at-edge` already implements the entire SAML flow, JWT issuance, and viewer/origin request pattern. You only need to add ~20 lines of attribute mapping code.

2. **Best architecture**: Authentication at the edge provides the lowest latency, highest performance (caching), and global scale. This is the modern, cloud-native approach.

3. **Eliminates Cognito completely**: No vendor lock-in, no ongoing MAU costs, full control over authentication/authorization logic.

4. **Protects everything**: Both UI and API behind the same security boundary. Single point of authentication enforcement.

5. **Pay-per-invocation cost model**: More economical than always-on ECS containers at scale.

6. **Proven pattern**: Lambda@Edge + CloudFront is a battle-tested approach for authentication at scale.

**Key consideration**: Infrastructure topology becomes more sophisticated (CloudFront added), but operational complexity remains reasonable for teams with AWS experience.

---

### **Alternative Recommendation: Option 3 - Cognito Federation**

**If your priority is speed and risk-minimization** (get Shibboleth working in production in 2 weeks with zero code changes), choose Federation.

You can always migrate from Federation → CloudFront later once you've validated the Shibboleth attribute mappings and authentication flow in production.

**Migration path**:
1. Deploy Federation first (1-2 weeks)
2. Validate Shibboleth attributes and group mappings
3. Build and test CloudFront solution in parallel
4. Cut over from Cognito → CloudFront (single deployment)
5. Decommission Cognito User Pool

---

### **Not Recommended: Option 2 - ECS Sidecar**

While technically sound and eliminates Cognito, it's strictly **worse** than CloudFront when you already have the Lambda@Edge code:
- More complex operational model (2 containers per task vs. serverless Lambda)
- Worse performance (no edge caching)
- Doesn't protect UI at origin
- More expensive at scale (always-on containers)

**Only choose Sidecar if**:
- CloudFront is organizationally or politically blocked
- You have strong ECS expertise and zero CloudFront/Lambda@Edge expertise
- You absolutely cannot add CloudFront to your architecture

---

## Next Steps

### If choosing Option 1 (CloudFront) - Recommended

1. **Week 1: Proof of Concept**
   - Clone `shibboleth-sp-at-edge` repo
   - Add attribute mapping to `FunctionSpOriginRequest.ts`
   - Generate JWT keypair
   - Deploy to dev environment

2. **Week 2: Integration**
   - Modify Spring Boot `SecurityConfiguration`
   - Deploy API changes to dev
   - Test JWT validation end-to-end
   - Verify authorization rules

3. **Week 3: UI + Testing**
   - Rewrite `ui/src/auth.ts`
   - Add auth helper endpoint
   - Deploy UI changes to dev
   - Full integration testing (login/logout/authorization)

4. **Week 4: Production**
   - BU IdP coordination (SP registration)
   - Terraform updates (point to CloudFront)
   - Production deployment
   - User acceptance testing

### If choosing Option 3 (Federation) - Fastest

1. **Week 1: Configuration**
   - Coordinate with BU IT (register Cognito as SP)
   - Configure Cognito SAML IdP
   - Map attributes to groups
   - Test in dev

2. **Week 2: Production**
   - Production deployment
   - User acceptance testing
   - Documentation

---

## Risk Assessment

### Option 1 (CloudFront)
- **Technical Risk**: ⭐⭐ Low-Medium (your code exists, just needs mapping)
- **Schedule Risk**: ⭐⭐ Low-Medium (2-3 weeks)
- **Operational Risk**: ⭐⭐ Low (serverless, proven pattern, but multi-region logs)

### Option 2 (Sidecar)
- **Technical Risk**: ⭐⭐⭐ Medium (container orchestration, JWT flow)
- **Schedule Risk**: ⭐⭐⭐ Medium (3-4 weeks)
- **Operational Risk**: ⭐⭐ Low (familiar ECS infrastructure)

### Option 3 (Federation)
- **Technical Risk**: ⭐ Very Low (configuration only)
- **Schedule Risk**: ⭐ Very Low (1-2 weeks)
- **Operational Risk**: ⭐⭐ Low (keeps Cognito dependency)

---

## Decision Checklist

Use this checklist to guide your decision:

- [ ] **Your `shibboleth-sp-at-edge` code is working and tested**
  - ✅ Yes → **Strong signal for Option 1**
  - ❌ No → Consider Option 3 initially

- [ ] **Eliminating Cognito is a priority**
  - ✅ Yes → Option 1 or 2
  - ❌ No → Option 3 is fine

- [ ] **You have CloudFront/Lambda@Edge expertise on the team**
  - ✅ Yes → **Option 1**
  - ❌ No → Option 3 (fastest) or invest in learning for Option 1 (best)

- [ ] **Performance and global scale matter**
  - ✅ Yes → **Option 1** (edge caching wins)
  - ❌ No → Any option works

- [ ] **Timeline pressure (need production in < 2 weeks)**
  - ✅ Yes → **Option 3** (then migrate to 1 later)
  - ❌ No → Option 1

- [ ] **Complex attribute mapping required**
  - ✅ Yes → **Option 1 or 2** (full control)
  - ❌ No → Option 3 is sufficient

---

## Conclusion

**Recommended: Option 1 - CloudFront + Lambda@Edge** for its superior architecture, existing codebase (90% complete), and elimination of Cognito.

**Alternative: Option 3 - Cognito Federation** if you need production Shibboleth in 1-2 weeks with zero code changes.

**Not recommended: Option 2 - ECS Sidecar** unless CloudFront is blocked—it's more complex than CloudFront operationally with worse performance and no unique advantages when Lambda@Edge code already exists.

---

## Appendix: Key Files to Modify

### Option 1 (CloudFront)
- `shibboleth-sp-at-edge/lib/lambda/FunctionSpOriginRequest.ts`
- `shibboleth-sp-at-edge/context/context.json`
- `api/src/main/java/edu/bu/archive/config/SecurityConfiguration.java`
- `api/src/main/java/edu/bu/archive/adapter/in/web/AuthCheckController.java` (new)
- `api/src/main/resources/application.yml`
- `ui/src/auth.ts`
- `terraform/environments/dev/main.tf` (CloudFront references)

### Option 2 (Sidecar)
- `Dockerfile.shibboleth-sp` (new)
- `terraform/modules/ecs/main.tf` (task definition)
- `api/src/main/java/edu/bu/archive/config/SecurityConfiguration.java`
- `api/src/main/resources/application.yml`
- `ui/src/auth.ts`

### Option 3 (Federation)
- **Cognito Console**: SAML IdP configuration
- **BU Shibboleth**: SP registration (metadata exchange)
- **Documentation**: User guides only

---

**Document Version**: 1.0  
**Date**: 2026-08-29  
**Branch**: `shibboleth`  
**Status**: Draft for review
