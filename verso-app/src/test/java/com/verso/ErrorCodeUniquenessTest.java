package com.verso;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.verso.platform.core.exception.ErrorCode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Every ErrorCode enum is globally unique and inside its block (reference 7.2). Runs in verso-app because this
 * module has every module on its classpath (the "aggregate" module the template asks for). Blocks mirror the README
 * table "Hata kodu bloklari".
 *
 * <p>Adapted from the reference blueprint template (tests/ErrorCodeUniquenessTest.java).
 */
class ErrorCodeUniquenessTest {

    /** service -> [min, max]; identical to the README table. */
    private static final Map<String, int[]> BLOCKS = Map.of(
            "validation", new int[]{90000, 90099},
            "security", new int[]{90100, 90199},
            "system", new int[]{99998, 99999},
            "document", new int[]{10000, 10999},
            "qa", new int[]{11000, 11999});

    @Test
    void errorCodes_whenScanned_areUniqueAndInsideTheirBlock() {
        JavaClasses classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .withImportOption(ArchitectureRulesTest.NO_TEST_JARS)
                .importPackages("com.verso");

        Map<Integer, String> seen = new HashMap<>();
        List<String> problems = new ArrayList<>();

        for (JavaClass jc : classes) {
            if (!jc.isEnum() || !jc.isAssignableTo(ErrorCode.class)) continue;
            Class<?> enumClass = jc.reflect();
            for (Object constant : enumClass.getEnumConstants()) {
                ErrorCode code = (ErrorCode) constant;
                String owner = enumClass.getName() + "." + ((Enum<?>) constant).name();
                String prev = seen.putIfAbsent(code.getCode(), owner);
                if (prev != null) {
                    problems.add("collision: " + code.getCode() + " -> " + prev + " and " + owner);
                }
                int[] block = BLOCKS.get(code.getService());
                if (block == null) {
                    problems.add("unknown service: " + code.getService() + " (" + owner + ")");
                } else if (code.getCode() < block[0] || code.getCode() > block[1]) {
                    problems.add("outside block: " + owner + " = " + code.getCode()
                            + " (expected " + block[0] + "-" + block[1] + ")");
                }
                if (code.getMessage() == null || code.getMessage().isBlank() || !code.getMessage().endsWith(".")) {
                    problems.add("message format: " + owner + " -> short English sentence ending with a period");
                }
            }
        }
        assertThat(problems).as("ErrorCode rules").isEmpty();
        assertThat(seen).as("the scan must see at least the common codes (empty set = false green)").isNotEmpty();
    }
}
