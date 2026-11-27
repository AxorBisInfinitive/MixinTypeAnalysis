package love.crysh.mixinta.runtime;

import love.crysh.mixinta.diagnostic.TypedOverwriteException;
import love.crysh.mixinta.testsupport.BytecodeFixtures;
import net.fabricmc.api.EnvType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ClassSourceIndexTest {
    @TempDir Path directory;

    @Test
    void recordsUnreadableSourcesWithoutFailingDiscoveryAndAggregatesGlobalFailures() throws Exception {
        Path broken = Files.write(directory.resolve("broken.class"), new byte[] {0});
        Path missing = directory.resolve("missing.class");
        Map<String, ClassSourceIndex.Source> sources = new LinkedHashMap<>();
        sources.put("sample/Broken", source("sample/Broken", broken));
        sources.put("sample/Missing", source("sample/Missing", missing));
        var index = new ClassSourceIndex(sources);
        assertTrue(index.inputs().isEmpty());
        assertEquals(ClassSourceIndex.Status.PARSE_FAILED, index.inspect("sample/Broken", EnvType.SERVER).status());
        assertEquals(ClassSourceIndex.Status.READ_FAILED, index.inspect("sample/Missing", EnvType.SERVER).status());
        var failure = assertThrows(TypedOverwriteException.class, index::verifyComplete);
        assertEquals(2, failure.diagnostics().size());
        assertTrue(failure.getMessage().contains("broken.class"));
        assertTrue(failure.getMessage().contains("PARSE_FAILED"));
        assertNotNull(index.inspect("sample/Missing", EnvType.SERVER).cause());
    }

    @Test
    void retainsEnvironmentAndDefinitionProvenance() throws Exception {
        var compiled = BytecodeFixtures.compile(Map.of("sample.Client", """
                package sample;
                @net.fabricmc.api.Environment(net.fabricmc.api.EnvType.CLIENT)
                public class Client {}
                """));
        Path file = Files.write(directory.resolve("Client.class"), compiled.get("sample/Client"));
        var source = source("sample/Client", file);
        var index = new ClassSourceIndex(Map.of("sample/Client", source));
        var input = index.inspect("sample/Client", EnvType.SERVER);
        assertEquals(ClassSourceIndex.Status.ENVIRONMENT_EXCLUDED, input.status());
        assertEquals(file.toUri().toURL(), input.source().resource());
        assertSame(getClass().getClassLoader(), input.source().definingLoader());
        assertNull(input.bytes());
        assertDoesNotThrow(index::verifyComplete);
    }

    private ClassSourceIndex.Source source(String name, Path file) throws Exception {
        return new ClassSourceIndex.Source(name, file.toUri().toURL(), directory, getClass().getClassLoader(), ClassSourceIndex.Status.DISCOVERED);
    }
}
