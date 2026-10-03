package com.verso.qa.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.verso.qa.api.dto.AnswerResponse;
import com.verso.qa.api.dto.Citation;
import com.verso.qa.api.dto.QuestionRequest;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.modulith.NamedInterface;

/** The contract module's rules (ADR-0007 #24): every package is the named interface "api"; values are records. */
class QaApiContractTest {

    @Test
    void packages_whenCompiled_areAllNamedInterfaceApi() throws Exception {
        Set<String> packages = new TreeSet<>();
        for (Resource resource : new PathMatchingResourcePatternResolver().getResources("classpath*:com/verso/qa/api/**/*.class")) {
            String path = resource.getURL().toString();
            String relative = path.substring(path.indexOf("com/verso/qa/api"));
            packages.add(relative.substring(0, relative.lastIndexOf('/')).replace('/', '.'));
        }
        assertThat(packages).contains("com.verso.qa.api", "com.verso.qa.api.dto");
        for (String name : packages) {
            NamedInterface named = Class.forName(name + ".package-info").getAnnotation(NamedInterface.class);
            assertThat(named).as(name).isNotNull();
            assertThat(named.value()).as(name).containsExactly("api");
        }
    }

    @Test
    void types_whenExposed_areRecords() {
        for (Class<?> type : List.of(QuestionRequest.class, AnswerResponse.class, Citation.class)) {
            assertThat(type.isRecord()).as(type.getSimpleName()).isTrue();
        }
    }
}
