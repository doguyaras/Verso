#!/usr/bin/env bash
# Negative verification ("a green rule alone proves nothing", reference 16, 19.6): every mutation below breaks one
# protection on purpose and must make the NAMED TEST METHOD fail. The tree is restored after each mutation and on any
# exit (trap). Output: one line per mutation; exit 0 only if the baseline is green and every mutation is caught.
#
# Usage:  JAVA_HOME=<jdk 25> GITLEAKS=<gitleaks 8.24.3> bash scripts/mutation-check.sh   (repository root)
#         ONLY="M39 M40" ... runs the baseline and only the listed mutations (while fixing a single protection).
# Evidence: docs/evidence/.
#
# Rules this script enforces on itself (lessons from phase 1, ADR-0007 #29, reference-feedback R15/R22):
#   * baseline first: a test that is already red would make a no-op mutation look "caught";
#   * a mutation counts as caught only when the expected test METHOD is listed among the failures/errors, so a
#     different red test cannot stand in for it; compile or resolution errors are reported as BUILD ERROR;
#   * every substitution must change the file, multi-part mutations check each part;
#   * restored files are touched (an older mtime would keep the mutated .class);
#   * a trap restores everything if the script is interrupted.
set -u
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
MVN="./mvnw -B -ntp"
# One log per run: a fixed path let two concurrent runs (two clones, or a reviewer's copy) overwrite each other's log,
# so one run could read the other's failures as its own "caught" (found in the third review round).
LOG="$(mktemp "${TMPDIR:-/tmp}/verso-mutation.XXXXXX")"
results=()
failed=0

# ---------- one run per working tree ----------
# Two concurrent runs on the same tree overwrite each other's .bak copies, and the "restore" then writes mutated
# content back (seen in phase 2: 22 files left mutated). mkdir is atomic; only the owner removes the lock.
# The per-tree git dir: in a linked worktree .git is a file, and mkdir under it always failed ("another run").
LOCK_DIR="$(git rev-parse --absolute-git-dir)/mutation-check.lock"
if ! mkdir "$LOCK_DIR" 2>/dev/null; then
  echo "mutation-check: another run holds $LOCK_DIR (started $(cat "$LOCK_DIR/started" 2>/dev/null || echo '?'))." >&2
  echo "mutation-check: wait for it, or remove the folder if that run is gone." >&2
  exit 3
fi
date -u +%Y-%m-%dT%H:%M:%SZ > "$LOCK_DIR/started"

# ---------- restore on any exit ----------
cleanup() {
  while IFS= read -r bak; do mv "$bak" "${bak%.bak}"; touch "${bak%.bak}"; done < <(find . -name '*.bak' -not -path './.git/*' -not -path './.claude/worktrees/*' 2>/dev/null)
  rm -rf verso-app/src/main/java/com/verso/stray verso-app/src/main/java/com/verso/platform tmp-mutation
  if [ -d verso-app/src/test.off ]; then rm -rf verso-app/src/test && mv verso-app/src/test.off verso-app/src/test; fi
  # M88 changes the git index, not a file: put the execute bit back even after an interrupt.
  git update-index --chmod=+x deploy/postgres/initdb/10-roles.sh 2>/dev/null || true
  rm -rf "$LOCK_DIR"
}
# An interrupted run restores the tree and stops: it used to carry on and could exit 0 (third-round review N4).
trap cleanup EXIT
trap 'cleanup; echo "INTERRUPTED"; exit 130' INT
trap 'cleanup; echo "INTERRUPTED"; exit 143' TERM

# want <id>: true when the mutation is selected (all of them unless ONLY is set).
want() { [ -z "${ONLY:-}" ] || [[ " $ONLY " == *" $1 "* ]]; }

backup() { cp "$1" "$1.bak"; }
restore() { mv "$1.bak" "$1"; touch "$1"; }
sub() { # file perl-substitution: fails (and records SETUP FAILED) if the file did not change
  local before; before=$(md5sum < "$1"); perl -0pi -e "$2" "$1"
  if [ "$before" = "$(md5sum < "$1")" ]; then results+=("SETUP FAILED  $1: $2"); failed=1; return 1; fi
}

# expect_red <id+description> <module> <TestClass> <expectedMethod>
expect_red() {
  want "${1%% *}" || return 0
  if $MVN -pl "$2" -am test -Dtest="$3" -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoTests=false > "$LOG" 2>&1; then
    results+=("NOT CAUGHT    $1"); failed=1; return
  fi
  if grep -qE "^\[ERROR\]   $3\.$4[:(]" "$LOG"; then
    results+=("caught        $1 ($3.$4)")
  elif grep -qE "^\[ERROR\]   [A-Za-z]+Test\.[A-Za-z0-9_]+[:(]" "$LOG"; then
    results+=("WRONG TEST    $1 (expected $3.$4; log: $LOG.${1%% *})"); cp "$LOG" "$LOG.${1%% *}"; failed=1
  else
    results+=("BUILD ERROR   $1 (no test failure; log: $LOG.${1%% *})"); cp "$LOG" "$LOG.${1%% *}"; failed=1
  fi
}

APP=verso-app/src
H=platform/platform-core/src/main/java/com/verso/platform/core/handler/GlobalServiceExceptionHandler.java
EC=platform/platform-core/src/main/java/com/verso/platform/core/handler/EnvelopeErrorController.java
CL=platform/platform-core/src/main/java/com/verso/platform/core/handler/ErrorClassifier.java
S=platform/platform-observability/src/main/java/com/verso/platform/observability/logging/SensitiveLogSanitizer.java
F=platform/platform-observability/src/main/java/com/verso/platform/observability/tracing/TraceIdFilter.java
OC=platform/platform-observability/src/main/java/com/verso/platform/observability/config/ObservabilityAutoConfiguration.java
PC=platform/platform-core/src/main/java/com/verso/platform/core/config/PlatformCoreAutoConfiguration.java
YML=$APP/main/resources/application.yml
GEH=GlobalServiceExceptionHandlerTest
SAN=SensitiveLogSanitizerTest
CEP=ContainerErrorPathTest
SMK=VersoAppSmokeTest

# ---------- baseline ----------
if ! $MVN verify > "$LOG" 2>&1; then
  echo "BASELINE RED: fix the build before measuring mutations (log: $LOG)"; exit 2
fi
# Module totals only: per-class lines repeat the same counts.
baseline_tests=$(grep -E '^\[INFO\] Tests run: [0-9]+, Failures: 0, Errors: 0, Skipped: [0-9]+$' "$LOG" \
  | sed -E 's/.*Tests run: ([0-9]+),.*/\1/' | awk '{s += $1} END {print s}')
results+=("baseline      green (${baseline_tests} tests)")
# The node suites are a baseline too: without gitleaks or node every one of them is red, and a mutation judged by
# them would look "caught" (third-round review N4).
NODE_SUITES="scripts/flyway-immutability.test.js scripts/config-lint.test.js scripts/gitleaks-check.test.js scripts/pre-commit.test.js scripts/review-gate.test.js scripts/repo-hygiene.test.js scripts/keycloak-start.test.js scripts/ollama-pull.test.js"
for suite in $NODE_SUITES; do
  if ! GITLEAKS="${GITLEAKS:-gitleaks}" node --test "$suite" > "$LOG" 2>&1; then
    echo "BASELINE RED: $suite (log: $LOG)"; exit 2
  fi
done
results+=("baseline      green (node suites: $(echo $NODE_SUITES | wc -w))")

# ---------- architecture and configuration ----------
mkdir -p $APP/main/java/com/verso/stray
printf 'package com.verso.stray;\n@org.springframework.context.annotation.Configuration\npublic class StrayConfig {}\n' > $APP/main/java/com/verso/stray/StrayConfig.java
expect_red "M01 @Configuration outside config/" verso-app ArchitectureRulesTest configurations_whenAnnotated_liveInConfigPackage
rm -rf $APP/main/java/com/verso/stray

mkdir -p $APP/main/java/com/verso/platform/leak
printf 'package com.verso.platform.leak;\npublic class Leak { Class<?> app = com.verso.VersoApp.class; }\n' > $APP/main/java/com/verso/platform/leak/Leak.java
expect_red "M02 platform -> application dependency" verso-app ArchitectureRulesTest platform_whenCompiled_doesNotDependOnApplicationModules
rm -rf $APP/main/java/com/verso/platform

mkdir -p $APP/main/java/com/verso/stray/a $APP/main/java/com/verso/stray/b
printf 'package com.verso.stray.a;\npublic class A { com.verso.stray.b.B b; }\n' > $APP/main/java/com/verso/stray/a/A.java
printf 'package com.verso.stray.b;\npublic class B { com.verso.stray.a.A a; }\n' > $APP/main/java/com/verso/stray/b/B.java
expect_red "M03 package cycle in production code" verso-app ArchitectureRulesTest packages_whenProductionCodeImported_haveNoCycles
rm -rf $APP/main/java/com/verso/stray

mkdir -p $APP/main/java/com/verso/stray
cat > $APP/main/java/com/verso/stray/StrayErrorCode.java <<'EOF'
package com.verso.stray;
import org.springframework.http.HttpStatus;
public enum StrayErrorCode implements com.verso.platform.core.exception.ErrorCode {
  DUPLICATE(90000, "Duplicate code.");
  private final int code; private final String message;
  StrayErrorCode(int c, String m) { code = c; message = m; }
  public int getCode() { return code; } public String getMessage() { return message; }
  public String getService() { return "validation"; } public HttpStatus getHttpStatus() { return HttpStatus.BAD_REQUEST; }
}
EOF
expect_red "M04 duplicate error code 90000" verso-app ErrorCodeUniquenessTest errorCodes_whenScanned_areUniqueAndInsideTheirBlock
rm -rf $APP/main/java/com/verso/stray

backup $APP/main/resources/application-local.yml
printf 'rate-limit:\n  rules:\n    question-account: { limit: 10, window-seconds: 60 }\n' >> $APP/main/resources/application-local.yml
expect_red "M05 local-only rate-limit scope (drift)" verso-app ConfigDriftTest mirroredKeys_whenLocalAndDeployCompared_areIdentical
restore $APP/main/resources/application-local.yml

backup $APP/main/resources/config/verso.yml
printf -- '---\nspring:\n  datasource:\n    password: ${SECRET_DB_PASSWORD:changeme}\n' >> $APP/main/resources/config/verso.yml
expect_red "M06 secret literal fallback" verso-app ConfigDriftTest secretKeys_whenConfigured_haveNoLiteralFallback
if want M06b; then
  node scripts/config-lint.js $APP/main/resources/config/verso.yml > "$LOG" 2>&1; rc=$?
  if [ "$rc" -eq 1 ] && grep -q "IHLAL" "$LOG"; then results+=("caught        M06b secret literal fallback (config-lint exit 1, IHLAL)")
  else results+=("NOT CAUGHT    M06b secret literal fallback (config-lint exit $rc)"); failed=1; fi
fi
restore $APP/main/resources/config/verso.yml

backup $APP/main/java/com/verso/VersoApp.java
sub $APP/main/java/com/verso/VersoApp.java 's/,\n\s*\@ComponentScan\.Filter\(type = FilterType\.REGEX[^\n]*\)\}\)/})/' \
  && expect_red "M07 platform starters component-scanned" verso-app $SMK platformStarters_whenAppStarts_areLoadedByAutoConfigurationNotComponentScan
restore $APP/main/java/com/verso/VersoApp.java

MS=$APP/test/java/com/verso/ModuleStructureTest.java
backup $MS
sub $MS 's/ApplicationModules\.of\(VersoApp\.class, resideInAPackage\("com\.verso\.platform\.\."\)\)/ApplicationModules.of(VersoApp.class)/' \
  && expect_red "M08 platform treated as an application module" verso-app ModuleStructureTest productionModules_whenDetected_matchExpectedSetAndExcludePlatform
restore $MS

if want M09; then
  backup pom.xml
  mkdir -p tmp-mutation/x-core tmp-mutation/y-core
  for m in x y; do
    dep=""
    if [ "$m" = y ]; then
      dep='<dependencies><dependency><groupId>com.verso</groupId><artifactId>x-core</artifactId><version>${revision}</version></dependency></dependencies>'
    fi
    printf '<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion><parent><groupId>com.verso</groupId><artifactId>verso</artifactId><version>${revision}</version><relativePath>../../pom.xml</relativePath></parent><artifactId>%s-core</artifactId>%s</project>\n' "$m" "$dep" > tmp-mutation/$m-core/pom.xml
  done
  if sub pom.xml 's#<module>verso-app</module>#<module>verso-app</module>\n    <module>tmp-mutation/x-core</module>\n    <module>tmp-mutation/y-core</module>#'; then
    if $MVN -q validate > "$LOG" 2>&1; then results+=("NOT CAUGHT    M09 core -> core dependency (enforcer)"); failed=1
    elif grep -q "ban-core-to-core" "$LOG" && grep -q "banned via the exclude/include list" "$LOG"; then
      results+=("caught        M09 core -> core dependency (enforcer ban-core-to-core)")
    else results+=("BUILD ERROR   M09 (unexpected failure; log: $LOG.M09)"); cp "$LOG" "$LOG.M09"; failed=1; fi
  fi
  restore pom.xml
  rm -rf tmp-mutation
fi

# ---------- error handling and privacy ----------
backup $H; sub $H 's/\(f\.isBindingFailure\(\) \? INVALID : ruleMessage\(f\)\)/f.getDefaultMessage()/' \
  && expect_red "M10 binding failure echoes raw message" platform/platform-core $GEH modelAttribute_whenTypeMismatch_returns400WithoutRejectedValueOrTypeNames; restore $H
backup $H; sub $H 's/if \(template == null \|\| template\.contains\("\$\{"\)\) return INVALID;/if (template == null) return INVALID;/' \
  && expect_red "M11 EL template rendered" platform/platform-core $GEH requestBody_whenMessagesUseExpressionsOrMapKeys_neverEchoClientData; restore $H
backup $H; sub $H 's/if \(node\.isInIterable\(\)\) path\.append\("\[\]"\);/if (node.isInIterable()) path.append("[" + node.getKey() + "]");/' \
  && expect_red "M11b map key kept in field path" platform/platform-core $GEH requestBody_whenMapKeyContainsBrackets_keepsOnlyThePropertyPath; restore $H
backup $H; sub $H 's/if \(error\.contains\(ConstraintViolation\.class\)\) \{/if (false) {/' \
  && expect_red "M45 field path parsed from the client string" platform/platform-core $GEH requestBody_whenListElementPropertyFails_keepsPropertyAfterTheIndex; restore $H
backup $H; sub $H 's/\.map\(v -> leafName\(v\) \+/.map(v -> v.getPropertyPath() +/' \
  && expect_red "M11c full property path with method name" platform/platform-core $GEH constraintViolation_whenFromMethodParameter_showsLeafNameOnly; restore $H
backup $H; sub $H 's/SensitiveLogSanitizer\.rootCauseType\(ex\), traceId\);\n        return envelope\(HttpStatus\.INTERNAL_SERVER_ERROR/SensitiveLogSanitizer.safeExceptionSummary(ex), traceId);\n        return envelope(HttpStatus.INTERNAL_SERVER_ERROR/' \
  && expect_red "M12 exception message in 500 log" platform/platform-core $GEH unexpectedException_whenThrown_returns500AndLogsTypesOnly; restore $H
backup $H; sub $H 's/(rootCauseType\(ex\), traceId)\);\n        return envelope\(HttpStatus\.INTERNAL_SERVER_ERROR/$1, ex);\n        return envelope(HttpStatus.INTERNAL_SERVER_ERROR/' \
  && expect_red "M13 throwable attached to 500 log event" platform/platform-core $GEH unexpectedException_whenThrown_returns500AndLogsTypesOnly; restore $H
backup $H; sub $H 's/headers\.putAll\(prepared\);//' \
  && expect_red "M14 Allow/Accept headers dropped" platform/platform-core $GEH httpMethod_whenNotSupported_returns405WithAllowHeader; restore $H
backup $H; sub $H 's/default -> new Mapping\(CommonErrorCode\.forStatus\(status\.value\(\)\), List\.of\(\)\);/default -> new Mapping(CommonErrorCode.VALIDATION, List.of());/' \
  && expect_red "M15 unmapped 4xx reported as validation" platform/platform-core $GEH responseStatusException_whenUnmappedStatus_usesCodeMatchingTheStatus; restore $H
backup $H; sub $H 's/\s*case MissingServletRequestParameterException e ->\n\s*new Mapping\(CommonErrorCode\.MISSING_PARAMETER, List\.of\("parameter=" \+ e\.getParameterName\(\)\)\);//' \
  && expect_red "M16 missing query parameter mapping removed" platform/platform-core $GEH requestParam_whenMissing_returns400NamingTheParameterOnly; restore $H
backup $H; sub $H 's/\@ExceptionHandler\(ConstraintViolationException\.class\)\n//' \
  && expect_red "M17 constraint violation handler removed" platform/platform-core $GEH constraintViolation_whenThrownByService_returns400WithSortedPathAndGenericValueMessage; restore $H
backup $H; sub $H 's/case HandlerMethodValidationException e -> new Mapping\(CommonErrorCode\.VALIDATION, parameterDetails\(e\)\);/case HandlerMethodValidationException e -> new Mapping(CommonErrorCode.VALIDATION, List.of());/' \
  && expect_red "M18 method validation details dropped" platform/platform-core $GEH requestParam_whenConstraintFails_returns400NamingTheParameterWithoutValue; restore $H
backup $H; sub $H 's/return envelope\(status, CommonErrorCode\.INTERNAL_ERROR, List\.of\(\), traceId, request, headers\);/return envelope(status, CommonErrorCode.VALIDATION, List.of(), traceId, request, headers);/' \
  && expect_red "M19 framework 5xx mapped to validation" platform/platform-core $GEH frameworkServerError_whenRaised_returnsInternalErrorAndLogsTypesOnly; restore $H

backup $S; sub $S 's/\)\{0,512\}\\"\|/){0,0}\\"|/' \
  && expect_red "M20 quoted secret values not redacted" platform/platform-observability $SAN sanitize_whenQuotedSecretValueContainsSpaces_redactsTheWholeValue; restore $S
backup $S; sub $S 's/s = IPV4\.matcher\(s\)\.replaceAll\("\[IP\]"\);//' \
  && expect_red "M21 IPv4 addresses not redacted" platform/platform-observability $SAN sanitize_whenIpAddresses_replacesThem; restore $S
backup $S; sub $S 's/String s = cut \? cutAtSeparator\(raw\) : raw;/String s = raw;/' \
  && expect_red "M22 input not cut before patterns (ReDoS)" platform/platform-observability $SAN sanitize_whenHugeCraftedInput_finishesQuickly; restore $S
backup $S
if sub $S 's/ && seen\.add\(root\) && depth\+\+ < MAX_CAUSE_DEPTH//' && sub $S 's/\s*if \(seen\.contains\(root\.getCause\(\)\)\) break;//'; then
  expect_red "M23 cyclic cause chain loops" platform/platform-observability $SAN rootCauseType_whenCauseChainIsCyclic_terminates
fi
restore $S
backup $S; sub $S 's/return last >= MAX_INPUT \/ 2 \? head\.substring\(0, last\) : head\.substring\(0, MAX_INPUT \/ 2\);/return head;/' \
  && expect_red "M39 cut leaves half a token" platform/platform-observability $SAN sanitize_whenCutFallsInsideSensitiveToken_dropsThePartialToken; restore $S
backup $S
if sub $S 's/\n\s*\+ "\|\(\?:\[0-9a-f\]\{1,4\}:\)\{1,7\}:[^\n]*//' \
  && sub $S 's/\+ "\|::\[0-9a-f\]\{1,4\}\(\?::\[0-9a-f\]\{1,4\}\)\{0,6\}\)/+ ")/'; then
  expect_red "M40 compressed IPv6 not redacted" platform/platform-observability $SAN sanitize_whenCompressedIpv6_replacesIt
fi
restore $S
backup $S; sub $S 's/\(\[A-Za-z0-9_\.-\]\{0,40\}\?\(\?:token/((?:token/' \
  && expect_red "M41 prefixed/camelCase secret keys missed" platform/platform-observability $SAN sanitize_whenSecretWordIsPartOfKey_redactsValue; restore $S
backup $S; sub $S 's/\n\s*s = AUTH_HEADER\.matcher[^\n]*//' \
  && expect_red "M51 Authorization with other schemes kept" platform/platform-observability $SAN sanitize_whenAuthorizationUsesAnyScheme_redactsSchemeAndCredential; restore $S
backup $S; sub $S 's/s = CONTROL\.matcher\(s\)\.replaceAll\(" "\);/s = s.replace(\x27\\r\x27, \x27 \x27).replace(\x27\\n\x27, \x27 \x27);/' \
  && expect_red "M52 Unicode separators and escapes kept" platform/platform-observability $SAN sanitize_whenUnicodeLineSeparatorsOrControlCharacters_replacesThem; restore $S
backup $S; sub $S 's/\|salt\|pepper//' \
  && expect_red "M60 salt/pepper keys missed" platform/platform-observability $SAN sanitize_whenSaltPepperPrivateKeyOrShortPasswordKeys_redactsValues; restore $S
TID=platform/platform-observability/src/main/java/com/verso/platform/observability/tracing/TraceIds.java
backup $TID; sub $TID 's/Pattern\.compile\("\[0-9a-f\]\{32\}"\)/Pattern.compile("[0-9a-zA-Z_-]{1,64}")/' \
  && expect_red "M61 loose MDC trace id reused" platform/platform-observability TraceIdFilterTest doFilter_whenMdcValueIsMalformed_generatesNewId; restore $TID
backup $H; sub $H 's/: fallbackMessage\(\);/: error.getDefaultMessage();/' \
  && expect_red "M62 custom Validator message echoed" platform/platform-core CustomValidatorMessageTest requestBody_whenSpringValidatorRejects_returnsGenericMessageWithoutValue; restore $H

# ---------- trace id and starters ----------
backup $F; sub $F 's/String traceId = TraceIds\.fromMdcOrNew\(\);/String inbound = request.getHeader(TraceIds.HEADER); String traceId = inbound != null ? inbound : TraceIds.fromMdcOrNew();/' \
  && expect_red "M24 client-supplied trace id trusted" platform/platform-observability TraceIdFilterTest doFilter_whenClientSendsTraceIdHeader_ignoresIt; restore $F
backup $F; sub $F 's/(        response\.setHeader\(TraceIds\.HEADER, traceId\);\n)(        chain\.doFilter\(request, response\);\n)/$2$1/' \
  && expect_red "M38 trace header set after the chain" platform/platform-observability TraceIdFilterTest doFilter_whenDownstreamCommitsResponse_headerIsAlreadySet; restore $F
backup $OC; sub $OC 's/registration\.addUrlPatterns\("\/\*"\);/registration.addUrlPatterns("\/v1\/*");/' \
  && expect_red "M25 trace filter limited to some paths" platform/platform-observability ObservabilityAutoConfigurationTest traceIdFilter_whenServletApplication_coversEveryPath; restore $OC
backup $OC; sub $OC 's/Ordered\.HIGHEST_PRECEDENCE \+ 2;/Ordered.HIGHEST_PRECEDENCE;/' \
  && expect_red "M26 trace filter ordered before observation" platform/platform-observability ObservabilityAutoConfigurationTest traceIdFilter_whenServletApplication_runsAfterObservationAndBeforeSecurity; restore $OC
backup $PC; sub $PC 's/\@ConditionalOnMissingBean\n(\s*GlobalServiceExceptionHandler)/$1/' \
  && expect_red "M27 handler does not back off" platform/platform-core PlatformCoreAutoConfigurationTest handler_whenApplicationDefinesOne_backsOff; restore $PC
backup $PC; sub $PC 's/\@ConditionalOnMissingBean\(ErrorController\.class\)\n//' \
  && expect_red "M37 error controller does not back off" platform/platform-core PlatformCoreAutoConfigurationTest errorController_whenApplicationDefinesOne_backsOff; restore $PC
backup $PC; sub $PC 's/\@AutoConfiguration\(before = ErrorMvcAutoConfiguration\.class\)/\@AutoConfiguration/' \
  && expect_red "M28 envelope error controller loses to BasicErrorController" verso-app $SMK platformStarters_whenAppStarts_areLoadedByAutoConfigurationNotComponentScan; restore $PC

# ---------- container error path and logs ----------
backup $YML; sub $YML 's/\n\s*org\.springframework\.web\.servlet\.PageNotFound: ERROR//' \
  && expect_red "M29 raw path logged by PageNotFound" verso-app $SMK unknownPath_whenRequested_isNotLogged; restore $YML
# Tomcat's host logger is silenced twice: the yml entry (first server, start-up) and ContainerErrorLogSilencer (every
# server by its real engine name). M31/M44 break both layers, M50 only the listener (third-round reviews B20, N1).
SIL=platform/platform-core/src/main/java/com/verso/platform/core/handler/ContainerErrorLogSilencer.java
SET_OFF='loggingSystem\.setLogLevel\(hostLoggerName\(engine\.getName\(\), host\.getName\(\)\), LogLevel\.OFF\);'
backup $YML; backup $SIL
if sub $YML 's/\n\s*"\[org\.apache\.catalina\.core\.ContainerBase[^\n]*OFF//' && sub $SIL "s/$SET_OFF/hostLoggerName(engine.getName(), host.getName());/"; then
  expect_red "M31 Tomcat logs filter exception message" verso-app $CEP filterException_whenUnexpected_returnsEnveloped500AndLogsTypesOnly
fi
restore $YML; restore $SIL
backup $YML; backup $SIL
if sub $YML 's/\[org\.apache\.catalina\.core\.ContainerBase\.\[Tomcat\]\.\[localhost\]\]": OFF/[org.apache.catalina.core.ContainerBase.[Tomcat].[localhost].[\/].[dispatcherServlet]]": OFF/' \
  && sub $SIL 's/hostLoggerName\(engine\.getName\(\), host\.getName\(\)\), LogLevel\.OFF/hostLoggerName(engine.getName(), host.getName()) + ".[\/].[dispatcherServlet]", LogLevel.OFF/'; then
  expect_red "M44 host logger prints failed error dispatch" verso-app $CEP filterException_whenErrorDispatchFailsToo_isNotLoggedAndNotEchoed
fi
restore $YML; restore $SIL
backup $SIL; sub $SIL "s/$SET_OFF/hostLoggerName(engine.getName(), host.getName());/" \
  && expect_red "M50 later Tomcat engines (management, Tomcat-N) not silenced" verso-app ContainerLogSilencingTest hostLoggers_whenApiAndManagementServersStart_areOffWhateverTheEngineName; restore $SIL
backup $YML; sub $YML 's/(formcontent:\n\s*filter:\n(?:\s*#[^\n]*\n)*\s*enabled: )false/${1}true/' \
  && expect_red "M32 form bodies parsed again" verso-app $CEP formBody_whenPut_isNotParsedIntoParameters; restore $YML
backup $YML; sub $YML 's/\n\s*org\.apache\.tomcat\.util\.http: WARN//' \
  && expect_red "M33 cookie parser logs raw header" verso-app $CEP cookieHeader_whenMalformed_isNotLogged; restore $YML
backup $CL; sub $CL 's/static boolean isMalformedRequest\(Throwable t\) \{/static boolean isMalformedRequest(Throwable t) {\n        if (t != null) return false;/' \
  && expect_red "M34 malformed request treated as server error" verso-app $CEP queryParameter_whenEncodingIsInvalid_returns400WithoutStackTrace; restore $CL
backup $CL; sub $CL 's/\n\s*\|\| TOMCAT_BAD_REQUEST\.equals\(current\.getClass\(\)\.getName\(\)\)//' \
  && expect_red "M64 broken chunked body ends as VALIDATION" verso-app $CEP chunkedBody_whenMalformed_returnsRequestNotReadable; restore $CL
backup $EC; sub $EC 's/if \(exception instanceof ServiceException se\) \{/if (false \&\& exception instanceof ServiceException se) {/' \
  && expect_red "M35 filter ServiceException loses its code" verso-app $CEP filterException_whenServiceException_keepsItsStatusAndCode; restore $EC
# Since phase 4 the validation starter also arrives through document-core: both have to go for the provider to go.
DCPOM=services/document/document-core/pom.xml
VALIDATION_STARTER='s/\s*<dependency>\s*<groupId>org\.springframework\.boot<\/groupId>\s*<artifactId>spring-boot-starter-validation<\/artifactId>\s*<\/dependency>//'
backup verso-app/pom.xml; backup $DCPOM; sub verso-app/pom.xml "$VALIDATION_STARTER" && sub $DCPOM "$VALIDATION_STARTER" \
  && expect_red "M36 no Bean Validation provider" verso-app $SMK beanValidation_whenAppStarts_hasARealProvider; restore verso-app/pom.xml; restore $DCPOM
backup $EC; sub $EC 's/return exception != null \? 500 : 404;/return 404;/' \
  && expect_red "M48 failure after commit reported as 404 WARN" verso-app $CEP filterException_whenResponseAlreadyCommitted_logsServerErrorAndWritesNothingMore; restore $EC
backup $EC; sub $EC 's/if \(response\.isCommitted\(\)\) \{/if (false) {/' \
  && expect_red "M49 envelope appended to a committed response" verso-app $CEP filterException_whenResponseAlreadyCommitted_logsServerErrorAndWritesNothingMore; restore $EC
backup $EC; sub $EC 's/\} else if \(exception != null && ErrorClassifier\.isMalformedRequest\(exception\)\) \{/} else if (false) {/' \
  && expect_red "M53 container 400 reported as server error" verso-app $CEP filterReadingParameter_whenEncodingIsInvalid_returns400FromErrorController; restore $EC
backup $EC; sub $EC 's/return value instanceof Throwable t \? ErrorClassifier\.unwrapServlet\(t\) : null;/return value instanceof Throwable t ? t : null;/' \
  && expect_red "M54 wrapped ServiceException loses its code" verso-app $CEP filterException_whenServiceExceptionWrappedInServletException_keepsCodeAndDetails; restore $EC
backup $YML; sub $YML 's/include: health,info/include: "*"/' \
  && expect_red "M55 every actuator endpoint exposed" verso-app $CEP actuator_whenSensitiveEndpointsRequested_areNotExposed; restore $YML

# ---------- phase 2: database roles, migrations, images (Testcontainers: needs Docker) ----------
DRT=DatabaseRolesTest
INIT=deploy/postgres/initdb
AFTER=services/document/document-core/src/main/resources/db/migration/document/afterMigrate.sql
backup $AFTER; sub $AFTER 's/^REVOKE ALL ON [^\n]*\n//m' \
  && expect_red "M65 history table left writable by the application" verso-app $DRT applicationRole_whenMigrationsRan_hasNoPrivilegeOnTheHistoryTable; restore $AFTER
backup $INIT/10-roles.sh; sub $INIT/10-roles.sh 's/\nALTER ROLE svc_\$\{schema\} SET statement_timeout = \x2710s\x27;//' \
  && expect_red "M66 application role without statement timeout" verso-app $DRT application_whenConnected_usesTheDmlRoleWithItsTimeoutsAndSearchPath; restore $INIT/10-roles.sh
backup $INIT/20-database.sh; sub $INIT/20-database.sh 's/\nGRANT USAGE ON SCHEMA \$\{schema\} TO svc_\$\{schema\};//' \
  && expect_red "M67 application role cannot reach its schema" verso-app $DRT migrationRoleTable_whenCreated_isWritableByTheApplicationRoleButNotDroppable; restore $INIT/20-database.sh
backup $INIT/05-settings.sh; sub $INIT/05-settings.sh 's/\nALTER SYSTEM SET log_parameter_max_length = 0;//' \
  && expect_red "M68 bind parameters logged by PostgreSQL" verso-app $DRT server_whenStarted_hasStatisticsAndNoParameterLogging; restore $INIT/05-settings.sh
backup $INIT/10-roles.sh; sub $INIT/10-roles.sh 's/\nREVOKE ALL ON DATABASE [^\n]*//' \
  && expect_red "M69 database open to PUBLIC" verso-app $DRT database_whenInitialized_isClosedToPublicAndOpenToTheNamedRoles; restore $INIT/10-roles.sh
VY=verso-app/src/main/resources/config/verso.yml
backup $VY; sub $VY 's/baseline-on-migrate: false/baseline-on-migrate: true/' \
  && expect_red "M70 baseline-on-migrate switched on" verso-app ConfigProfilesTest database_whenLocalProfile_isLocalhostWithTheProductionRoles; restore $VY
MCT=services/document/document-core/src/test/java/com/verso/document/migration/MigrationConventionsTest.java
backup $MCT; sub $MCT 's/\n\s*Pattern\.compile\("\(\?i\)\\\\bcreate\\\\s\+schema\\\\b"\),//' \
  && expect_red "M71 CREATE SCHEMA allowed in migrations" services/document/document-core MigrationConventionsTest violations_whenFixturesBreakEachRule_areAllReported; restore $MCT
backup compose.yaml; sub compose.yaml 's/(x-postgres-image: &postgres-image \S+)\@sha256:[0-9a-f]{64}/$1/' \
  && expect_red "M72 compose image not pinned by digest" verso-app ImageVersionsTest images_whenReferencedInComposeOrDockerfile_arePinnedByDigest; restore compose.yaml
backup compose.yaml; sub compose.yaml 's/pgvector\/pgvector:0\.8\.7-pg18-trixie/pgvector\/pgvector:0.8.6-pg18-trixie/' \
  && expect_red "M73 tests and compose on different PostgreSQL images" verso-app ImageVersionsTest postgresImage_whenUsedByTestsAndCompose_isTheSame; restore compose.yaml

# ---------- phase 2 review fixes ----------
backup $INIT/05-settings.sh; sub $INIT/05-settings.sh 's/\nALTER SYSTEM SET log_error_verbosity = \x27terse\x27;//' \
  && expect_red "M74 constraint DETAIL (row content) in server log" verso-app $DRT constraintViolation_whenRowCarriesDocumentText_neverReachesTheServerLog; restore $INIT/05-settings.sh
AFTERERR=services/document/document-core/src/main/resources/db/migration/document/afterMigrateError.sql
backup $AFTERERR; sub $AFTERERR 's/^REVOKE ALL ON [^\n]*\n//m' \
  && expect_red "M75 history left writable after a failed migrate" services/document/document-core MigrationConventionsTest callbacks_whenPresent_revokeTheHistoryTableFromTheApplicationRole; restore $AFTERERR
backup compose.yaml; sub compose.yaml 's/(    secrets:\n      - SECRET_DB_DOCUMENT_PASSWORD\n)(    # Not published)/$1      - SECRET_DB_DOCUMENT_MIGRATE_PASSWORD\n$2/' \
  && expect_red "M76 application container gets the migration password" verso-app ComposeConfigTest migrationPassword_whenComposed_reachesOnlyTheOneShotMigrateService; restore compose.yaml
backup .dockerignore; sub .dockerignore 's/\n\*\*\/\.env\n/\n/' \
  && expect_red "M77 nested .env files enter the build context" verso-app ComposeConfigTest dockerignore_whenBuilding_keepsSecretsAndEnvFilesOutAtEveryDepth; restore .dockerignore
backup compose.yaml; sub compose.yaml 's/(  postgres:\n    image: \*postgres-image\n)/$1    ports:\n      - "5432:5432"\n/' \
  && expect_red "M78 database published on the host" verso-app ComposeConfigTest ports_whenComposed_publishOnlyTheApiAndTheIdpOnLoopback; restore compose.yaml
backup compose.yaml; sub compose.yaml 's/\n\s*- --management\.endpoint\.health\.validate-group-membership=false//' \
  && expect_red "M79 migrate mode cannot start (readiness group needs db)" verso-app MigrateModeTest migrateMode_whenStartedWithTheComposeArguments_runsFlywayWithoutApplicationDataSource; restore compose.yaml

# ---------- phase 2 test review survivors (R01-R16), now pinned ----------
# Statements replaced by a no-op, not deleted: an empty for-loop body would be a bash syntax error (no init at all).
backup $INIT/10-roles.sh; sub $INIT/10-roles.sh 's/\nREVOKE ALL ON DATABASE [^\n]*\nGRANT CONNECT ON DATABASE [^\n]*//' \
  && sub $INIT/10-roles.sh 's/GRANT CONNECT ON DATABASE \\"\$POSTGRES_DB\\" TO svc_\$\{schema\}, svc_\$\{schema\}_migrate;/SELECT 1;/' \
  && expect_red "M80 database ACL never set (PUBLIC keeps implicit CONNECT)" verso-app $DRT database_whenInitialized_isClosedToPublicAndOpenToTheNamedRoles; restore $INIT/10-roles.sh
backup $INIT/10-roles.sh; sub $INIT/10-roles.sh 's/CREATE ROLE svc_\$\{schema\} LOGIN PASSWORD/CREATE ROLE svc_\${schema} LOGIN CREATEROLE PASSWORD/' \
  && expect_red "M81 application role may create roles" verso-app $DRT roles_whenCreated_haveNoElevatedAttributes; restore $INIT/10-roles.sh
backup $INIT/05-settings.sh; sub $INIT/05-settings.sh 's/password_encryption = \x27scram-sha-256\x27/password_encryption = \x27md5\x27/' \
  && expect_red "M82 passwords hashed with md5" verso-app $DRT server_whenStarted_hashesPasswordsWithScram; restore $INIT/05-settings.sh
backup $INIT/10-roles.sh; sub $INIT/10-roles.sh 's/sql="SET log_min_error_statement = panic;"/sql=""/' \
  && expect_red "M83 failing CREATE ROLE logs its password" verso-app $DRT rolesScript_whenCreateRoleFails_doesNotLogThePassword; restore $INIT/10-roles.sh
backup $INIT/20-database.sh; sub $INIT/20-database.sh 's/GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES/GRANT ALL ON TABLES/' \
  && expect_red "M84 default privileges wider than DML" verso-app $DRT migrationRoleObjects_whenCreated_giveTheApplicationRoleExactlyDmlAndSequenceUsage; restore $INIT/20-database.sh
backup $VY; sub $VY 's/password: \$\{SECRET_DB_DOCUMENT_PASSWORD\}/password: \${SECRET_DB_DOCUMENT_MIGRATE_PASSWORD}/' \
  && expect_red "M85 application datasource uses the migration password" verso-app DeployConfigTest deployConfig_whenFedLikeCompose_connectsAsTheApplicationRoleToTheVersoDatabase; restore $VY
backup Dockerfile; sub Dockerfile 's/\nUSER 10001//' \
  && expect_red "M86 image runs as root" verso-app ComposeConfigTest dockerfile_whenFinalStageRuns_usesANonRootUser; restore Dockerfile
backup compose.yaml; sub compose.yaml 's/(  backup:\n)    <<: \*hardening\n/$1/' \
  && expect_red "M87 backup container without hardening" verso-app ComposeConfigTest hardening_whenServicesStart_isReadOnlyWithoutCapabilities; restore compose.yaml

# ---------- phase 3: identity (ADR-0005, ADR-0010) ----------
JV=platform/platform-security/src/main/java/com/verso/platform/security/jwt/JwtValidation.java
JP=platform/platform-security/src/main/java/com/verso/platform/security/jwt/VersoJwtProperties.java
PSA=platform/platform-security/src/main/java/com/verso/platform/security/config/PlatformSecurityAutoConfiguration.java
PSM=platform/platform-security
PST=PlatformSecurityTest
REFUSED=request_whenTokenIsRefused_isRejectedWith401InvalidToken
REALM=deploy/keycloak/realm/verso-realm.json
backup $JV; sub $JV 's/\.jwsAlgorithm\(ALGORITHM\)/.jwsAlgorithms(a -> { a.add(ALGORITHM); a.add(SignatureAlgorithm.RS256); })/' \
  && expect_red "M89 RS256 accepted next to ES256" $PSM $PST $REFUSED; restore $JV
backup $JV; sub $JV 's/\n\s*validators\.add\(new JwtIssuerValidator\(properties\.issuer\(\)\)\);//' \
  && expect_red "M90 issuer not checked" $PSM $PST $REFUSED; restore $JV
backup $JV; sub $JV 's/jwt\.getAudience\(\) != null && jwt\.getAudience\(\)\.contains\(properties\.audience\(\)\)/true/' \
  && expect_red "M91 audience not checked" $PSM $PST $REFUSED; restore $JV
backup $JV; sub $JV 's/jwt -> jwt\.getExpiresAt\(\) != null/jwt -> true/' \
  && expect_red "M92 token without exp accepted" $PSM $PST $REFUSED; restore $JV
backup $JV; sub $JV 's/jwt\.getSubject\(\) != null && SUBJECT\.matcher\(jwt\.getSubject\(\)\)\.matches\(\)/jwt.getSubject() != null/' \
  && expect_red "M93 any sub becomes an account id" $PSM $PST $REFUSED; restore $JV
backup $JV; sub $JV 's/if \(!properties\.typeHeader\(\)\.isBlank\(\)\)/if (false)/' \
  && expect_red "M94 typ header ignored (ID token accepted)" $PSM $PST $REFUSED; restore $JV
backup $JP; sub $JP 's/!"https"\.equals\(scheme\) && !"http"\.equals\(scheme\)/false/' \
  && expect_red "M95 JWKS from a non-HTTP URL" $PSM SecurityUnitTest properties_whenIncompleteOrUnsafe_failAtStartup; restore $JP
backup $VY; sub $VY 's/\n\s*type-header: at\+jwt//' \
  && expect_red "M96 application config does not require at+jwt" verso-app $SMK api_whenTokenIsNotAnAccessTokenForVerso_isRefused; restore $VY
backup $H; sub $H 's/\n\s*if \(ErrorClassifier\.isSecurityException\(ex\)\) throw ex;//' \
  && expect_red "M97 access denied becomes a 500" $PSM $PST request_whenControllerDeniesAccess_isRejectedWith403EnvelopeNot500; restore $H
backup $PSA; sub $PSA 's/if \(ACTUATOR_PRESENT\) \{/if (false) {/' \
  && expect_red "M98 health probes need a token" verso-app $SMK probesAndApi_whenCalledWithoutToken_probesAnswerAndApiRefuses; restore $PSA
backup $PSA; sub $PSA 's/EnvelopeRequestRejectedHandler envelopeRequestRejectedHandler\(/org.springframework.security.web.firewall.RequestRejectedHandler envelopeRequestRejectedHandler(/' \
  && sub $PSA 's/return new EnvelopeRequestRejectedHandler\(resolver\);/return new org.springframework.security.web.firewall.DefaultRequestRejectedHandler();/' \
  && expect_red "M99 firewall rejection outside the envelope" verso-app $SMK dotSegmentPath_whenRequested_isRejectedAndNotLogged; restore $PSA
backup compose.yaml; sub compose.yaml 's/"127\.0\.0\.1:\$\{VERSO_KEYCLOAK_PORT:-8180\}:8080"/"\${VERSO_KEYCLOAK_PORT:-8180}:8080"/' \
  && expect_red "M100 IdP published on every interface" verso-app ComposeConfigTest ports_whenComposed_publishOnlyTheApiAndTheIdpOnLoopback; restore compose.yaml
backup compose.yaml; sub compose.yaml 's/(  keycloak:\n    image: [^\n]*\n    restart: always\n    security_opt: \[[^\n]*\]\n)    cap_drop: \[ALL\]\n/$1/' \
  && expect_red "M101 IdP container keeps its capabilities" verso-app ComposeConfigTest hardening_whenServicesStart_isReadOnlyWithoutCapabilities; restore compose.yaml
backup $INIT/30-keycloak.sh; sub $INIT/30-keycloak.sh 's/\nREVOKE ALL ON DATABASE keycloak FROM PUBLIC;//' \
  && expect_red "M102 Verso roles can connect to the IdP database" verso-app $DRT keycloakDatabase_whenInitialized_isSeparatedFromVerso; restore $INIT/30-keycloak.sh
backup $REALM; sub $REALM 's/"directAccessGrantsEnabled": false/"directAccessGrantsEnabled": true/' \
  && expect_red "M103 password grant enabled" verso-app KeycloakRealmTest clients_whenDeclared_useOnlyDeviceFlowOrClientCredentials; restore $REALM
backup $REALM; sub $REALM 's/("verso-ci".*?"access\.token\.header\.type\.rfc9068": )"true"/$1"false"/s' \
  && expect_red "M104 CI client tokens without at+jwt" verso-app KeycloakRealmTest clients_whenDeclared_useOnlyDeviceFlowOrClientCredentials; restore $REALM
backup $REALM; sub $REALM 's/"secret": "\$\{VERSO_CI_CLIENT_SECRET\}"/"secret": "ci-secret-in-git"/' \
  && expect_red "M105 literal client secret in the public realm file" verso-app KeycloakRealmTest secrets_whenRealmIsCommitted_areOnlyPlaceholdersFilledByTheStartScript; restore $REALM
backup $REALM; sub $REALM 's/"defaultSignatureAlgorithm": "ES256"/"defaultSignatureAlgorithm": "RS256"/' \
  && expect_red "M106 IdP signs with RS256" verso-app KeycloakRealmTest realm_whenImported_signsShortLivedTokensWithEs256Only; restore $REALM

# ---------- phase 3 review fixes (C1-C8, S1-S6, T1-T13, E1, R1-R2) ----------
CAR=platform/platform-security/src/main/java/com/verso/platform/security/web/CurrentAccountArgumentResolver.java
CAC=platform/platform-security/src/main/java/com/verso/platform/security/web/CurrentAccountParameterCheck.java
ADH=platform/platform-security/src/main/java/com/verso/platform/security/web/EnvelopeAccessDeniedHandler.java
SUT=SecurityUnitTest
backup $PSA; sub $PSA 's/\@ConditionalOnMissingBean\(name = "versoApiSecurity"\)/\@ConditionalOnMissingBean(SecurityFilterChain.class)/' \
  && expect_red "M107 another security chain switches the API chain off" $PSM $PST api_whenAnotherSecurityChainIsAdded_staysProtected; restore $PSA
backup $CAR; sub $CAR 's/return parameter\.hasParameterAnnotation\(CurrentAccount\.class\);/return parameter.hasParameterAnnotation(CurrentAccount.class) \&\& AccountId.class.equals(parameter.getParameterType());/' \
  && expect_red "M108 @CurrentAccount String left to request binding" $PSM $SUT currentAccount_whenParameterIsNotAnAccountId_isClaimedAndRefused; restore $CAR
backup $CAC; sub $CAC 's/verify\(mapping\.getHandlerMethods\(\)\.values\(\)\);/mapping.getHandlerMethods();/' \
  && expect_red "M109 wrongly typed @CurrentAccount starts anyway" $PSM $PST startup_whenCurrentAccountAnnotatesAnotherType_fails; restore $CAC
backup $JV; sub $JV 's/\.rateLimited\(REFETCH_MIN_INTERVAL\.toMillis\(\)\)/.rateLimited(false)/' \
  && expect_red "M110 every made-up kid fetches the JWKS" $PSM $PST request_whenManyTokensCarryUnknownKeyIds_fetchesTheKeysAtMostOnce; restore $JV
backup $JV; sub $JV 's/                return List\.of\(\);\n/                throw e;\n/' \
  && expect_red "M111 rate-limited unknown kid answered as an IdP outage" $PSM $PST request_whenManyTokensCarryUnknownKeyIds_fetchesTheKeysAtMostOnce; restore $JV
backup $JV; sub $JV 's/\n\s*if \(!loaded\.get\(\)\) throw e;//' \
  && expect_red "M112 cold-cache outage answered as an invalid token" $PSM $PST request_whenIdpKeysAreUnreachable_isRejectedWith503; restore $JV
backup $PSA; sub $PSA 's/failures\.setRethrowAuthenticationServiceException\(false\);/failures.setRethrowAuthenticationServiceException(true);/' \
  && expect_red "M113 unreachable IdP becomes a 500" $PSM $PST request_whenIdpKeysAreUnreachable_isRejectedWith503; restore $PSA
backup $JV; sub $JV 's/new DefaultResourceRetriever\(timeout, timeout, JWKS_SIZE_LIMIT\)/new DefaultResourceRetriever(0, 0, JWKS_SIZE_LIMIT)/' \
  && expect_red "M114 JWKS fetch without timeout" $PSM $SUT decoder_whenJwksDoesNotAnswer_failsWithinTheConfiguredTimeout; restore $JV
backup $JV; sub $JV 's/\n\s*timestamps\.setClock\(clock\);//' \
  && expect_red "M115 token times checked with the system clock" $PSM $SUT validator_whenTimeIsChecked_usesTheConfiguredSkewAndTheInjectedClock; restore $JV
backup $JV; sub $JV 's/return value\.startsWith\("application\/"\) \? value\.substring\("application\/"\.length\(\)\) : value;/return value;/' \
  && expect_red "M116 application/at+jwt refused" $PSM $SUT validator_whenTypeHeaderIsTheFullMediaType_acceptsIt; restore $JV
backup $JP; sub $JP 's/if \("http"\.equals\(scheme\) && !allowHttp && !isLoopback\(jwkSetUri\.getHost\(\)\)\)/if (false)/' \
  && expect_red "M117 plain-HTTP JWKS to a remote host" $PSM $SUT properties_whenIncompleteOrUnsafe_failAtStartup; restore $JP
backup $JP; sub $JP 's/!jwksTimeout\.isPositive\(\) \|\| jwksTimeout\.compareTo\(Duration\.ofSeconds\(10\)\) > 0/false/' \
  && expect_red "M118 JWKS timeout zero or unbounded" $PSM $SUT properties_whenIncompleteOrUnsafe_failAtStartup; restore $JP
backup $CL; sub $CL 's/(t == null \? null : t\.getClass\(\); type != null; )type = type\.getSuperclass\(\)/$1type = null/' \
  && expect_red "M119 @PreAuthorize denial becomes a 500" $PSM $PST request_whenControllerThrowsAuthorizationDeniedException_isRejectedWith403EnvelopeNot500; restore $CL
backup $ADH; sub $ADH 's/\n\s*response\.setHeader\("WWW-Authenticate", "Bearer error=\\"insufficient_scope\\""\);//' \
  && expect_red "M120 403 without insufficient_scope" $PSM $PST request_whenControllerDeniesAccess_isRejectedWith403EnvelopeNot500; restore $ADH
backup $PSA; sub $PSA 's/SessionCreationPolicy\.STATELESS/SessionCreationPolicy.ALWAYS/' \
  && expect_red "M121 sessions created" $PSM $PST responses_whenAuthenticatedOrRejected_setNoSessionCookie; restore $PSA
backup $PSA; sub $PSA 's/EndpointRequest\.to\("health"\)/EndpointRequest.toAnyEndpoint()/' \
  && expect_red "M122 every actuator endpoint open without a token" verso-app $SMK probesAndApi_whenCalledWithoutToken_probesAnswerAndApiRefuses; restore $PSA
backup $INIT/30-keycloak.sh; sub $INIT/30-keycloak.sh 's/SELECT NOT EXISTS \(SELECT 1 FROM pg_roles WHERE rolname = \x27keycloak\x27\) AS create_role/SELECT true AS create_role/' \
  && expect_red "M123 IdP database script not rerunnable (upgrade path)" verso-app $DRT keycloakScript_whenRunAgainOrWithoutItsSecret_isIdempotentAndFailsClosed; restore $INIT/30-keycloak.sh
backup $REALM; sub $REALM 's/("clientId": "admin-cli",\n[^\n]*\n\s*"enabled": )false/$1true/' \
  && expect_red "M124 admin-cli (password grant) enabled" verso-app KeycloakRealmTest builtInAndCliClients_whenImported_allowNoPasswordGrantOrOfflineTokens; restore $REALM
backup $REALM; sub $REALM 's/"failureFactor": 5/"failureFactor": 100000/' \
  && expect_red "M125 unlimited login attempts" verso-app KeycloakRealmTest realm_whenImported_limitsLoginAttemptsAndSessions; restore $REALM
backup compose.yaml; sub compose.yaml 's/(      - SECRET_KEYCLOAK_DEMO_USER_PASSWORD\n)/$1      - SECRET_DB_DOCUMENT_PASSWORD\n/' \
  && expect_red "M126 IdP container gets a Verso database password" verso-app ComposeConfigTest migrationPassword_whenComposed_reachesOnlyTheOneShotMigrateService; restore compose.yaml

# ---------- phase 4: document ingestion (ADR-0011) ----------
DOC=services/document/document-core/src/main/java/com/verso/document
DCM=services/document/document-core
DSVC=$DOC/service/impl/DocumentServiceImpl.java
DREPO=$DOC/repository/DocumentRepository.java
IREPO=$DOC/repository/IngestionRepository.java
IWK=$DOC/worker/IngestionWorker.java
PDFX=$DOC/worker/PdfTextExtractor.java
DAT=DocumentApiTest
IWT=IngestionWorkerTest
DLT=DocumentLimitsTest
PXT=PdfTextExtractorTest
backup $IREPO; sub $IREPO 's/\n\s*OR \(status = \x27PROCESSING\x27 AND locked_until <= :now\)//' \
  && expect_red "M128 expired leases are never taken over" verso-app $IWT claim_whenLeaseExpired_isTakenOverAndTheOldWorkerCannotComplete; restore $IREPO
backup $IREPO; sub $IREPO 's/SET updated_at = :now WHERE id = :id AND claim_token = :token/SET updated_at = :now WHERE id = :id/' \
  && expect_red "M129 a stale worker can still write" verso-app $IWT claim_whenLeaseExpired_isTakenOverAndTheOldWorkerCannotComplete; restore $IREPO
backup $IREPO; sub $IREPO 's/\n\s*for \(String table : List\.of\("document_chunk", "document_page", "document_file"\)\) \{\n[^\n]*\n\s*\}//' \
  && expect_red "M130 FAILED documents keep their content" verso-app $IWT runOnce_whenPdfIsUnusable_failsAtOnceWithItsReasonAndKeepsNoContent; restore $IREPO
backup $IREPO; sub $IREPO 's/\n\s*jdbc\.sql\("DELETE FROM document\.document_file WHERE document_id = :id"\)\.param\("id", documentId\)\.update\(\);\n    \}/\n    }/' \
  && expect_red "M131 the PDF is kept after parsing" verso-app $IWT runOnce_whenPdfHasText_storesPagesChunksAndVectorsAndDropsThePdf; restore $IREPO
backup $DREPO; sub $DREPO 's/FROM document\.document WHERE account_id = :account AND id = :id/FROM document.document WHERE (true OR account_id = :account) AND id = :id/' \
  && expect_red "M132 another account's document can be read" verso-app $DAT documents_whenAccessedByAnotherAccount_doNotExistForIt; restore $DREPO
backup $DREPO; sub $DREPO 's/DELETE FROM document\.document WHERE account_id = :account AND id = :id/DELETE FROM document.document WHERE (true OR account_id = :account) AND id = :id/' \
  && expect_red "M133 another account's document can be deleted" verso-app $DAT documents_whenAccessedByAnotherAccount_doNotExistForIt; restore $DREPO
backup $DREPO; sub $DREPO 's/FROM document\.document WHERE account_id = :account "\n(\s*)\+ "ORDER BY/FROM document.document WHERE (true OR account_id = :account) "\n$1+ "ORDER BY/' \
  && expect_red "M134 the list shows every account's documents" verso-app $DAT documents_whenAccessedByAnotherAccount_doNotExistForIt; restore $DREPO
backup $DSVC; sub $DSVC 's/\n\s*if \(!startsLikePdf\(content\)\) throw rejected\(DocumentErrorCode\.DOCUMENT_NOT_PDF\);//' \
  && expect_red "M135 any file is accepted as a PDF" verso-app $DAT upload_whenFileIsNotAPdfOrEmpty_isRejectedWithDocumentCodes; restore $DSVC
backup $DSVC; sub $DSVC 's/\n\s*if \(earlier\.isPresent\(\)\) return replayed\(earlier\.get\(\)\);//' \
  && sub $DSVC 's/return replayed\(repository\.findByIdempotencyKey\(account\.value\(\), idempotencyKey\)\.orElseThrow\(\(\) -> e\)\);/throw e;/' \
  && expect_red "M136 a retried upload is not idempotent" verso-app $DAT upload_whenRepeatedWithTheSameIdempotencyKey_returnsTheFirstDocument; restore $DSVC
backup $DSVC; sub $DSVC 's/>= properties\.maxDocumentsPerAccount\(\)/>= Integer.MAX_VALUE/' \
  && expect_red "M137 no document quota" verso-app $DLT upload_whenQueueOrQuotaIsFull_isRejectedUntilItFreesUp; restore $DSVC
backup $DSVC; sub $DSVC 's/>= properties\.maxQueuedPerAccount\(\)/>= Integer.MAX_VALUE/' \
  && expect_red "M138 one account can fill the queue" verso-app $DLT upload_whenQueueOrQuotaIsFull_isRejectedUntilItFreesUp; restore $DSVC
backup $DSVC; sub $DSVC 's/\n\s*repository\.lockAccount\(account\.value\(\)\);//' \
  && expect_red "M139 parallel uploads race the quota" verso-app $DLT upload_whenSentInParallel_neverExceedsTheLimits; restore $DSVC
backup $DSVC; sub $DSVC 's/\n\s*repository\.waitForLocksUpTo\(DELETE_LOCK_WAIT\);//' \
  && expect_red "M140 KVKK delete fails behind the worker's lock" verso-app $DAT delete_whenTheRowIsLockedForSeconds_waitsAndSucceeds; restore $DSVC
backup $PDFX; sub $PDFX 's/\+\+pageGlyphs > maxPageGlyphs \|\| //' \
  && expect_red "M141 glyphs of one page are not bounded" $DCM $PXT extract_whenAPageHoldsMoreGlyphsThanAllowed_isRejectedWhileCollecting; restore $PDFX
backup $PDFX; sub $PDFX 's/\n\s*measureContent\(document\);//' \
  && expect_red "M142 content inflates without a limit" $DCM $PXT extract_whenContentInflatesBeyondTheLimit_isRejectedBeforeDecoding; restore $PDFX
backup $PDFX; sub $PDFX 's/if \(getGraphicsStackSize\(\) > MAX_GRAPHICS_STACK\)/if (false)/' \
  && expect_red "M143 the graphics state stack grows without a limit" $DCM $PXT extract_whenGraphicsStatesPileUp_isRejectedAsUnsupported; restore $PDFX
backup $PDFX; sub $PDFX 's/\} else \{\n\s*throw rejected\(DocumentFailureReason\.UNSUPPORTED_PDF\);\n\s*\}\n\s*if \(!flate\)/} else {\n            flate = false;\n        }\n        if (!flate)/' \
  && expect_red "M144 unmeasurable stream encodings are trusted" $DCM $PXT extract_whenAContentStreamUsesAnUnmeasurableEncoding_isRejectedAsUnsupported; restore $PDFX
backup $PDFX; sub $PDFX 's/CONTROL\.matcher\(text\.replace\("\\r\\n", "\\n"\)\.replace\(\x27\\r\x27, \x27\\n\x27\)\)\.replaceAll\(" "\)/text.replace("\\r\\n", "\\n").replace(\x27\\r\x27, \x27\\n\x27)/' \
  && expect_red "M145 NUL and control characters reach the database" $DCM $PXT normalize_whenTextHasControlCharactersAndRuns_cleansThem; restore $PDFX
backup $IWK; sub $IWK 's/            pause\(e\);\n\s*release\(claim, e\.misconfigured \? "MODEL_MISCONFIGURED" : "MODEL_UNAVAILABLE", started\);/            retryOrFail(claim, e, started);/' \
  && expect_red "M146 a model outage spends the document's attempts" verso-app $IWT runOnce_whenTheModelIsUnavailable_releasesWithoutAnAttemptAndPauses; restore $IWK
backup $IWK; sub $IWK 's/\n\s*pausedUntil = clock\.instant\(\)\.plus\(pause\);//' \
  && expect_red "M147 no circuit breaker: claims go on while the model is down" verso-app $IWT runOnce_whenTheModelIsUnavailable_releasesWithoutAnAttemptAndPauses; restore $IWK
backup $IWK; sub $IWK 's/if \(NON_TRANSIENT_AI\.equals\(type\.getName\(\)\)\) return true;/if (false) return true;/' \
  && expect_red "M148 a misconfigured model is treated as an outage" verso-app $IWT runOnce_whenTheModelIsMisconfigured_pausesWithAnErrorAndSpendsNoAttempt; restore $IWK
backup $IWK; sub $IWK 's/if \(!running\) throw new Stopping\(\);/if (false) throw new Stopping();/g' \
  && expect_red "M149 shutdown does not hand the document back" verso-app $IWT runOnce_whenShutdownBeginsDuringEmbedding_releasesTheDocument; restore $IWK
backup $IWK; sub $IWK 's/if \(vector\.length != properties\.embeddingDimensions\(\)\) throw new ModelFailure\(true, "WRONG_DIMENSIONS"\);//' \
  && expect_red "M150 vectors of another dimension are not caught" verso-app $IWT runOnce_whenTheModelAnswersWithOtherDimensions_storesNothingAndPauses; restore $IWK
backup $IREPO; sub $IREPO 's/attempts = GREATEST\(attempts - 1, 0\)/attempts = attempts/' \
  && expect_red "M151 a released document loses an attempt" verso-app $IWT runOnce_whenTheModelIsUnavailable_releasesWithoutAnAttemptAndPauses; restore $IREPO
backup $CL; sub $CL 's/(current\.getClass\(\); type != null; )type = type\.getSuperclass\(\)/$1type = null/' \
  && expect_red "M163 a lock timeout (a DataAccess subclass) answers 500" verso-app DatabaseUnavailableTest request_whenALockCannotBeTaken_answers503WithRetryAfter; restore $CL
backup $H; sub $H 's/if \(ErrorClassifier\.isTemporarilyUnavailable\(ex\)\) \{/if (false) {/' \
  && expect_red "M152 database outage answers 500" verso-app DatabaseUnavailableTest request_whenTheDatabaseIsUnreachable_answers503WithRetryAfter; restore $H
backup $DOC/controller/UploadLimiter.java; sub $DOC/controller/UploadLimiter.java 's/if \(!permits\.tryAcquire\(\)\)/if (false)/' \
  && expect_red "M153 unbounded uploads in memory" $DCM UploadLimiterTest preHandle_whenAllPermitsAreTaken_rejectsWithRetryAfterAndFreesThemAfterCompletion; restore $DOC/controller/UploadLimiter.java
backup $DOC/service/FileNames.java; sub $DOC/service/FileNames.java 's/\n\s*if \(slash >= 0\) name = name\.substring\(slash \+ 1\);//' \
  && expect_red "M154 file names keep their path" $DCM FileNamesTest sanitize_whenNameCarriesAPath_keepsOnlyTheLastSegment; restore $DOC/service/FileNames.java
backup $DOC/worker/PageChunker.java; sub $DOC/worker/PageChunker.java 's/\n\s*if \(space > start\) end = space;//' \
  && expect_red "M155 chunks split words" $DCM PageChunkerTest chunk_whenWordsFitTheWindow_cutsAtWhitespaceAndOverlaps; restore $DOC/worker/PageChunker.java
backup $DOC/controller/DocumentController.java; sub $DOC/controller/DocumentController.java 's/ResponseEntity\.created\(URI\.create\("\/v1\/documents\/" \+ created\.id\(\)\)\)\.cacheControl\(PRIVATE\)/ResponseEntity.created(URI.create("\/v1\/documents\/" + created.id()))/' \
  && expect_red "M156 uploaded file names cached by proxies" verso-app $DAT upload_whenPdf_returns201WithLocationAndAPendingPrivateDocument; restore $DOC/controller/DocumentController.java
backup $VY; sub $VY 's/supported: "1\.0"/supported: "1.0, 2.0"/' \
  && expect_red "M157 unknown API versions accepted" verso-app $DAT apiVersion_whenAbsentOrSupportedOrUnknown_isAcceptedOrRejected; restore $VY
backup compose.yaml; sub compose.yaml 's/\n\s*- --spring\.ai\.model\.embedding=none//' \
  && expect_red "M158 migrate run builds a model client" verso-app MigrateModeTest migrateMode_whenStartedWithTheComposeArguments_runsFlywayWithoutApplicationDataSource; restore compose.yaml
backup compose.yaml; sub compose.yaml 's/(  models:\n)    internal: true/$1    internal: false/' \
  && expect_red "M159 the model server can reach the internet" verso-app ComposeConfigTest ollama_whenComposed_isIsolatedReadOnlyAndServesThePulledModel; restore compose.yaml
backup $DOC/worker/IngestionWorker.java; sub $DOC/worker/IngestionWorker.java 's/\@ConditionalOnWebApplication\(type = ConditionalOnWebApplication\.Type\.SERVLET\)\npublic class IngestionWorker/public class IngestionWorker/' \
  && expect_red "M160 the worker would run in the migrate container" verso-app MigrateModeTest migrateMode_whenStartedWithTheComposeArguments_runsFlywayWithoutApplicationDataSource; restore $DOC/worker/IngestionWorker.java
V1=$DCM/src/main/resources/db/migration/document/V1__document_tables.sql
backup $V1; sub $V1 's/\nREVOKE UPDATE ON document\.document_chunk FROM svc_document;//' \
  && expect_red "M161 application role may rewrite stored chunks" verso-app $DRT documentTables_whenMigrated_giveTheApplicationRoleNoUpdateOnStoredContent; restore $V1


# ---------- phase 5: question answering ----------
QS=services/qa/qa-core/src/main/java/com/verso/qa
QCM=services/qa/qa-core
QAT=QuestionApiTest
RTT=RetrievalTest
RREPO=$DOC/repository/RetrievalRepository.java
RSVC=$DOC/service/impl/DocumentRetrievalServiceImpl.java
QSVC=$QS/service/impl/QuestionServiceImpl.java
backup $RREPO; sub $RREPO 's/WHERE c\.account_id = :account AND c\.embedding_model/WHERE c.embedding_model/' \
  && expect_red "M164 another account's passages are searched" verso-app $RTT search_whenAnotherAccountHasTheSameTopic_returnsOnlyTheCallersPassages; restore $RREPO
backup $RREPO; sub $RREPO "s/ AND d\.status = 'READY'//" \
  && expect_red "M165 documents that are not READY are searched" verso-app $RTT search_whenADocumentIsNotReady_ignoresIt; restore $RREPO
backup $RSVC; sub $RSVC 's/if \(repository\.hasChunksOfAnotherModel\(accountId, model\)\) \{/if (false) {/' \
  && expect_red "M166 vectors of another model are compared" verso-app $RTT search_whenChunksCameFromAnotherModel_failsClosed; restore $RSVC
backup $RSVC; sub $RSVC 's/return call\.get\(timeout\.toMillis\(\), TimeUnit\.MILLISECONDS\);/return call.get(600_000L, TimeUnit.MILLISECONDS);/' \
  && expect_red "M167 a hanging embedding model holds the question" verso-app $RTT search_whenTheEmbeddingModelHangs_givesUpAfterTheTimeout; restore $RSVC
backup $RSVC; sub $RSVC 's/if \(!embeddingSlots\.tryAcquire\(\)\) \{/if (false) {/' \
  && expect_red "M168 unbounded question embeddings" verso-app $RTT search_whenAllEmbeddingSlotsAreTaken_failsFastWithoutCallingTheModel; restore $RSVC
backup $QSVC; sub $QSVC 's/\.filter\(p -> p\.similarity\(\) >= properties\.minSimilarity\(\)\)/.filter(p -> true)/' \
  && expect_red "M169 the model is asked below the threshold" verso-app $QAT ask_whenNothingIsRelevant_answersNotFoundWithoutCallingTheModel; restore $QSVC
backup $QSVC; sub $QSVC 's/if \(circuit\.isOpen\(\)\) \{/if (false) {/' \
  && expect_red "M170 no circuit breaker on the chat model" verso-app $QAT ask_whenTheChatModelKeepsFailing_opensTheCircuitAndRecovers; restore $QSVC
backup $QS/service/ModelCircuitBreaker.java; sub $QS/service/ModelCircuitBreaker.java 's/consecutiveFailures >= properties\.circuitFailures\(\)/consecutiveFailures > properties.circuitFailures()/' \
  && expect_red "M171 the circuit opens one failure late" $QCM ModelCircuitBreakerTest opensAfterConsecutiveFailures_andOnlyForThePause; restore $QS/service/ModelCircuitBreaker.java
backup $QS/service/PromptBuilder.java; sub $QS/service/PromptBuilder.java "s/return folded\.replace\('\[', '\('\)\.replace\('\]', '\)'\);/return folded;/" \
  && expect_red "M172 passages can imitate a fence" $QCM PromptBuilderTest build_whenAPassageOrTheQuestionImitatesAFence_defusesIt; restore $QS/service/PromptBuilder.java
backup $QS/service/PromptBuilder.java; sub $QS/service/PromptBuilder.java 's/Normalizer\.normalize\(text, Normalizer\.Form\.NFKC\)/text/' \
  && expect_red "M173 full-width look-alikes pass the fence" $QCM PromptBuilderTest defuse_whenDataImitatesAFenceOrAMarker_leavesNoBracketAndIsIdempotent; restore $QS/service/PromptBuilder.java
backup $QS/service/CitationExtractor.java; sub $QS/service/CitationExtractor.java 's/if \(from < 1 \|\| to > passages\.size\(\) \|\| from > to\) continue;/if (from > to) continue;/' \
  && expect_red "M174 an invented citation number is used" $QCM CitationExtractorTest extract_whenAMarkerIsInvented_removesItAndNeverCitesIt; restore $QS/service/CitationExtractor.java
backup $VY; sub $VY 's/\n    retry:\n      max-attempts: 0//' \
  && expect_red "M175 the chat client retries a failing model" verso-app OllamaChatClientTest call_whenTheModelFails_asksExactlyOnceAndLogsNoProviderText; restore $VY
backup $VY; sub $VY 's/\n        think: false//' \
  && expect_red "M176 reasoning models think away the answer budget" verso-app OllamaChatClientTest call_sendsTheConfiguredModelAndOptions; restore $VY
backup $H; sub $H 's/(phase 5 api review P2\)[^\n]*\n[^\n]*\n[^\n]*\n\s*headers = new HttpHeaders\(\);)\n\s*headers\.set\(HttpHeaders\.RETRY_AFTER, RETRY_AFTER_SECONDS\);/$1/' \
  && expect_red "M177 a 503 without Retry-After" platform/platform-core $GEH serviceException_whenServerSideWithCause_logsErrorWithoutCauseText; restore $H
backup $H; sub $H 's/ && !hasRetryAfter\(request\)//' \
  && expect_red "M178 the IdP outage's Retry-After is overwritten" $PSM $PST request_whenIdpKeysAreUnreachable_isRejectedWith503; restore $H
backup $QS/controller/QuestionBodyLimit.java; sub $QS/controller/QuestionBodyLimit.java 's/if \(length > MAX_BODY_BYTES\)/if (false)/' \
  && expect_red "M179 a huge question body is parsed" verso-app $QAT ask_whenTheBodyIsHugeOrHasNoLength_isRefusedBeforeParsing; restore $QS/controller/QuestionBodyLimit.java
MCI=$QCM/src/main/resources/META-INF/spring/org.springframework.boot.actuate.autoconfigure.web.ManagementContextConfiguration.imports
backup $MCI; sub $MCI 's/com\.verso\.qa\.config\.QaManagementContextConfiguration\n//' \
  && expect_red "M180 the management port has no X-Rag-Mode" verso-app $QAT actuatorInfo_whenAsked_namesTheModeAndModels; restore $MCI
backup $QS/config/QaConfiguration.java; sub $QS/config/QaConfiguration.java 's/\n\s*response\.setHeader\(MODE_HEADER, mode\);//' \
  && expect_red "M181 responses do not say which mode answered" verso-app $QAT ragModeHeader_whenTheRequestIsRejectedOrUnknown_isStillPresent; restore $QS/config/QaConfiguration.java


# ---------- phase 6: cloud mode and egress (ADR-0013) ----------
QCFG=$QS/config/QaConfiguration.java
MCHK=$QS/config/AiModeCheck.java
NGX=deploy/edge/nginx.conf
backup $QCFG; sub $QCFG 's/return \(\) -> AiModeCheck\.verify\(ai\.mode\(\), environment\);/return () -> { };/' \
  && expect_red "M183 the startup check is not wired" verso-app AiModeStartupTest start_whenLocalModeIsGivenACloudProvider_fails; restore $QCFG
backup $QCFG; sub $QCFG 's/return InetAddressFilter\.internalAddresses\(\);/return InetAddressFilter.all();/' \
  && expect_red "M184 Boot clients may connect anywhere" verso-app OllamaChatClientTest outboundClients_whenAddressIsPublic_refuseToConnect_andInternalOnesWork; restore $QCFG
backup $VY; sub $VY 's/(    anthropic:\n)      max-retries: 0\n/$1/' \
  && expect_red "M185 the Anthropic SDK retries" verso-app AnthropicCloudModeTest ask_whenTheProviderFails_asksOnceAndAnswers503WithoutProviderText; restore $VY
backup $VY; sub $VY 's/(        model: \$\{CLOUD_CHAT_MODEL\}\n)        max-retries: 0\n/$1/; s/(          timeout: 30s\n)          max-retries: 0\n/$1/' \
  && expect_red "M186 the OpenAI SDK retries" verso-app OpenAiCloudModeTest ask_whenTheProviderFails_asksOnce; restore $VY
backup $QSVC; sub $QSVC 's/response = call\.get\(chatTimeout\(\)\.toMillis\(\), TimeUnit\.MILLISECONDS\);/response = call.get(600_000L, TimeUnit.MILLISECONDS);/' \
  && expect_red "M187 a hanging chat model holds the question" verso-app $QAT ask_whenTheModelHangs_answers503AtTheChatTimeout; restore $QSVC
backup $QSVC; sub $QSVC 's/ai\.mode\(\) == AiMode\.CLOUD \? properties\.cloudChatTimeout\(\) : properties\.localChatTimeout\(\)/properties.localChatTimeout()/' \
  && expect_red "M188 cloud calls get the local 90 s" verso-app OpenAiCloudModeTest ask_whenTheProviderIsSlow_answers503AtTheServiceBound; restore $QSVC
backup $NGX; sub $NGX 's/(location \@too_large \{\n\s*default_type application\/json;\n)\s*add_header X-Rag-Mode \$rag_mode always;\n/$1/' \
  && expect_red "M189 the proxy's 413 hides the mode" verso-app ComposeConfigTest edgeProxy_whenConfigured_keepsTheContractAndLogsNothing; restore $NGX
backup $NGX; sub $NGX 's/error_log \/dev\/stderr crit;/error_log \/dev\/stderr warn;/' \
  && expect_red "M190 the proxy logs client addresses and request lines" verso-app ComposeConfigTest edgeProxy_whenConfigured_keepsTheContractAndLogsNothing; restore $NGX
backup compose.yaml; sub compose.yaml 's/(\/var\/lib\/postgresql:size=\$\{RESTORE_TMPFS_SIZE:-1g\}\n)    networks: \[drill\]\n/$1/' \
  && expect_red "M191 the restored copy has a route out" verso-app ComposeConfigTest networks_whenComposed_keepTheApplicationWithoutARouteOutUnlessCloud; restore compose.yaml
backup compose.yaml; sub compose.yaml 's/    networks: \[backend, models\]/    networks: [backend, models, default]/' \
  && expect_red "M192 the application has a route out in local mode" verso-app ComposeConfigTest networks_whenComposed_keepTheApplicationWithoutARouteOutUnlessCloud; restore compose.yaml
backup $MCHK; sub $MCHK 's/if \(environment instanceof ConfigurableEnvironment configurable && \(definedIn/if (false \&\& environment instanceof ConfigurableEnvironment configurable \&\& (definedIn/' \
  && expect_red "M193 the API key may come from an environment variable" $QCM AiModeCheckTest cloud_whenTheKeyComesFromTheEnvironmentOrSdkLoggingIsOn_refusesToStart; restore $MCHK
backup $MCHK; sub $MCHK 's/\n\s*requireInternalIfLiteral\(environment\.getProperty\(OLLAMA_BASE_URL, ""\)\);//' \
  && expect_red "M194 a public Ollama address is accepted" $QCM AiModeCheckTest local_whenOllamaIsAPublicAddress_refusesToStart; restore $MCHK
backup $VY; sub $VY 's/(        model: \$\{CLOUD_CHAT_MODEL\}\n)(        # No temperature)/$1        temperature: 0.1\n$2/' \
  && expect_red "M195 Claude gets a sampling value it refuses" verso-app AnthropicCloudModeTest ask_whenAPassageMatches_sendsOnlyRulesQuestionAndPassagesToTheProvider; restore $VY


# ---------- phase 7: observability (ADR-0014) ----------
IMET=$DOC/worker/IngestionMetrics.java
PSAC=platform/platform-security/src/main/java/com/verso/platform/security/config/PlatformSecurityAutoConfiguration.java
backup $IMET; sub $IMET 's/\.strongReference\(true\)//' \
  && expect_red "M196 the paused gauge is held weakly and reads NaN" verso-app MetricsTest outageGauges_whenModelsFail_turnFromZeroToOne; restore $IMET
backup $PSAC; sub $PSAC 's/managementPort::matches/request -> true/' \
  && expect_red "M197 the token-free scrape follows the actuator onto the API port" verso-app MetricsSharedPortTest prometheus_whenTheActuatorSharesTheApiPort_needsAToken; restore $PSAC
backup deploy/obs/alloy/config.alloy; sub deploy/obs/alloy/config.alloy 's/\n  rule \{\n    source_labels = \["__meta_docker_container_label_com_docker_compose_service"\]\n    regex         = "verso-app\|migrate\|edge\|backup"\n    action        = "keep"\n  \}//' \
  && expect_red "M198 every container's log goes to Loki" verso-app ComposeConfigTest observability_whenComposed_isOptInInternalAndQuiet; restore deploy/obs/alloy/config.alloy
backup compose.yaml; sub compose.yaml 's/(can leave the host \(phase 7 review B1\)\. obs-edge publishes it on 127\.0\.0\.1\. The settings below say the same\.\n    networks: )\[obs\]/$1\[obs, default\]/' \
  && expect_red "M199 Grafana has a route out" verso-app ComposeConfigTest observability_whenComposed_isOptInInternalAndQuiet; restore compose.yaml

# ---------- scripts and hooks ----------
# node_red <id+description> <test file> <expected test name prefix>: like expect_red for node --test suites. The node
# suites passed in the baseline, so a red run here comes from the mutation, not from a missing node or gitleaks.
node_red() {
  want "${1%% *}" || return 0
  if GITLEAKS="${GITLEAKS:-gitleaks}" node --test "$2" > "$LOG" 2>&1; then results+=("NOT CAUGHT    $1"); failed=1
  elif grep -qF "✖ $3" "$LOG"; then results+=("caught        $1 ($2: $3)")
  else results+=("WRONG TEST    $1 (expected $3; log: $LOG.${1%% *})"); cp "$LOG" "$LOG.${1%% *}"; failed=1; fi
}
backup scripts/gitleaks-check.sh; sub scripts/gitleaks-check.sh 's/run staged git --staged "\$TARGET"/run staged dir "\$TARGET"/' \
  && node_red "M42 staged scan replaced by tree scan" scripts/gitleaks-check.test.js "staged modu: index"; restore scripts/gitleaks-check.sh
PC_HOOK=.githooks/pre-commit
backup $PC_HOOK; sub $PC_HOOK 's/gitleaks-check\.sh staged \./gitleaks-check.sh tree ./' \
  && node_red "M56 pre-commit scans the working tree" scripts/pre-commit.test.js "staged secret"; restore $PC_HOOK
backup $PC_HOOK; sub $PC_HOOK 's/  node scripts\/config-lint\.js "\$\{staged_files\[@\]\}"/  true/' \
  && node_red "M57 pre-commit skips config-lint" scripts/pre-commit.test.js "staged fallback secret"; restore $PC_HOOK
backup $PC_HOOK; sub $PC_HOOK 's/flyway-immutability\.js check --staged/flyway-immutability.js check/' \
  && node_red "M58 pre-commit checks migrations in the working tree" scripts/pre-commit.test.js "base'teki migration"; restore $PC_HOOK
backup scripts/flyway-immutability.js; sub scripts/flyway-immutability.js 's/\.\.\.\(staged \? \[\x27--cached\x27\] : \[\]\), //' \
  && node_red "M59 flyway --staged ignored" scripts/flyway-immutability.test.js "--staged:"; restore scripts/flyway-immutability.js
backup scripts/config-lint.js; sub scripts/config-lint.js 's/\n\s*\.replace\(\/\(\[a-z0-9\]\)\(\[A-Z\]\)\/g, \x27\$1 \$2\x27\)//' \
  && node_red "M63 camelCase secret keys not split" scripts/config-lint.test.js "(b) camelCase"; restore scripts/config-lint.js
# The git index, not a file: the trap does not know about it, so the bit is restored right after the run.
if want M88; then
  git update-index --chmod=-x deploy/postgres/initdb/10-roles.sh
  node_red "M88 initdb script not executable in git" scripts/repo-hygiene.test.js "shell scripts and git hooks"
  git update-index --chmod=+x deploy/postgres/initdb/10-roles.sh
fi
D=.claude/hooks/review-gate-detect.js
backup $D; sub $D 's/\n\s*\/\/ any other quoted value[^\n]*\n[^\n]*\x27Q\x27\);/;/' \
  && node_red "M43 quoted -C path splits (push not detected)" scripts/review-gate.test.js "third-round review B23"; restore $D
backup scripts/config-lint.pathspec; sub scripts/config-lint.pathspec 's/\n[^\n]*\*\.yaml//' \
  && node_red "M47 .yaml configs not linted" scripts/config-lint.test.js "pathspec: tum Spring config"; restore scripts/config-lint.pathspec
KS=deploy/keycloak/start.sh
backup $KS; sub $KS 's/  if \[ ! -r "\$SECRETS\/\$1" \] \|\| \[ ! -s "\$SECRETS\/\$1" \]; then/  if false; then/' \
  && node_red "M127 IdP starts with a missing secret" scripts/keycloak-start.test.js "start.sh: missing SECRET_DB_KEYCLOAK_PASSWORD"; restore $KS
PULL=deploy/ollama/pull.sh
backup $PULL; sub $PULL 's/if ! grep -qF "\\"\$digest\\"" "\$manifest"; then/if false; then/' \
  && node_red "M162 a model with another digest is accepted" scripts/ollama-pull.test.js "ollama-pull: another digest"; restore $PULL
backup $PULL; sub $PULL 's/\[0-9a-f\]\{64\}/[0-9a-f]{2,64}/' \
  && node_red "M182 a digest prefix passes as a pin" scripts/ollama-pull.test.js "ollama-pull: a digest prefix"; restore $PULL

# ---------- zero tests must fail the build ----------
if want M30; then
  mv $APP/test $APP/test.off
  mkdir -p $APP/test/java/com/verso
  printf 'package com.verso;\nclass ZeroTestsTest {\n  void notATest() {}\n}\n' > $APP/test/java/com/verso/ZeroTestsTest.java
  if $MVN -pl verso-app -am test > "$LOG" 2>&1; then results+=("NOT CAUGHT    M30 module running zero tests passes"); failed=1
  elif grep -q "No tests were executed" "$LOG"; then results+=("caught        M30 module running zero tests (No tests were executed)")
  else results+=("BUILD ERROR   M30 (unexpected failure)"); failed=1; fi
  rm -rf $APP/test && mv $APP/test.off $APP/test
  find $APP/test -name '*.java' -exec touch {} +
fi

# A typo in ONLY used to run nothing and exit 0 (third-round review N4): every requested id must have a result.
for id in ${ONLY:-}; do
  if ! printf '%s\n' "${results[@]}" | grep -qE "^[A-Za-z ]+ +$id "; then results+=("UNKNOWN ID    $id"); failed=1; fi
done

printf '%s\n' "${results[@]}"
echo "--- log of the last step: $LOG (failed steps keep a copy as $LOG.<id>)"
echo "--- leftovers:"; git status --short | grep -E '\.bak|stray|leak|tmp-mutation|ZeroTests|test\.off' || echo "none"
exit $failed
