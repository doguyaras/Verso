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

# ---------- restore on any exit ----------
cleanup() {
  while IFS= read -r bak; do mv "$bak" "${bak%.bak}"; touch "${bak%.bak}"; done < <(find . -name '*.bak' -not -path './.git/*' 2>/dev/null)
  rm -rf verso-app/src/main/java/com/verso/stray verso-app/src/main/java/com/verso/platform tmp-mutation
  if [ -d verso-app/src/test.off ]; then rm -rf verso-app/src/test && mv verso-app/src/test.off verso-app/src/test; fi
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
NODE_SUITES="scripts/flyway-immutability.test.js scripts/config-lint.test.js scripts/gitleaks-check.test.js scripts/pre-commit.test.js scripts/review-gate.test.js scripts/repo-hygiene.test.js"
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
  && expect_red "M29 raw path logged by PageNotFound" verso-app $SMK dotSegmentPath_whenRequested_isNotLogged; restore $YML
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
backup verso-app/pom.xml; sub verso-app/pom.xml 's/\s*<dependency>\s*<groupId>org\.springframework\.boot<\/groupId>\s*<artifactId>spring-boot-starter-validation<\/artifactId>\s*<\/dependency>//' \
  && expect_red "M36 no Bean Validation provider" verso-app $SMK beanValidation_whenAppStarts_hasARealProvider; restore verso-app/pom.xml
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
backup compose.yaml; sub compose.yaml 's/(    secrets:\n      - SECRET_DB_DOCUMENT_PASSWORD\n    ports:)/    secrets:\n      - SECRET_DB_DOCUMENT_PASSWORD\n      - SECRET_DB_DOCUMENT_MIGRATE_PASSWORD\n    ports:/' \
  && expect_red "M76 application container gets the migration password" verso-app ComposeConfigTest migrationPassword_whenComposed_reachesOnlyTheOneShotMigrateService; restore compose.yaml
backup .dockerignore; sub .dockerignore 's/\n\*\*\/\.env\n/\n/' \
  && expect_red "M77 nested .env files enter the build context" verso-app ComposeConfigTest dockerignore_whenBuilding_keepsSecretsAndEnvFilesOutAtEveryDepth; restore .dockerignore
backup compose.yaml; sub compose.yaml 's/(  postgres:\n    image: \*postgres-image\n)/$1    ports:\n      - "5432:5432"\n/' \
  && expect_red "M78 database published on the host" verso-app ComposeConfigTest ports_whenComposed_publishOnlyTheApiOnLoopback; restore compose.yaml
backup compose.yaml; sub compose.yaml 's/\n\s*- --management\.endpoint\.health\.validate-group-membership=false//' \
  && expect_red "M79 migrate mode cannot start (readiness group needs db)" verso-app MigrateModeTest migrateMode_whenStartedWithTheComposeArguments_runsFlywayWithoutApplicationDataSource; restore compose.yaml

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
D=.claude/hooks/review-gate-detect.js
backup $D; sub $D 's/\n\s*\/\/ any other quoted value[^\n]*\n[^\n]*\x27Q\x27\);/;/' \
  && node_red "M43 quoted -C path splits (push not detected)" scripts/review-gate.test.js "third-round review B23"; restore $D
backup scripts/config-lint.pathspec; sub scripts/config-lint.pathspec 's/\n[^\n]*\*\.yaml//' \
  && node_red "M47 .yaml configs not linted" scripts/config-lint.test.js "pathspec: tum Spring config"; restore scripts/config-lint.pathspec

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
