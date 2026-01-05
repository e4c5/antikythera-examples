package sa.com.cloudsolutions.antikythera.examples;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import sa.com.cloudsolutions.antikythera.evaluator.AntikytheraRunTime;
import sa.com.cloudsolutions.antikythera.generator.RepositoryQuery;
import sa.com.cloudsolutions.antikythera.parser.BaseRepositoryParser;
import net.sf.jsqlparser.statement.Statement;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class JPARepositoryAnalyzerTest {

    private JPARepositoryAnalyzer analyzer;
    private MockedStatic<AntikytheraRunTime> antikytheraRunTimeMock;
    private MockedStatic<BaseRepositoryParser> baseRepositoryParserMock;

    @BeforeEach
    void setUp() {
        analyzer = new JPARepositoryAnalyzer();
        antikytheraRunTimeMock = mockStatic(AntikytheraRunTime.class);
        baseRepositoryParserMock = mockStatic(BaseRepositoryParser.class);
    }

    @AfterEach
    void tearDown() {
        antikytheraRunTimeMock.close();
        baseRepositoryParserMock.close();
    }

    @Test
    void testWriteQueryToCsv_TypicalCase() throws Exception {
        // Prepare data
        RepositoryQuery query = mock(RepositoryQuery.class);
        when(query.getRepositoryClassName()).thenReturn("com.example.Repo");
        when(query.getMethodName()).thenReturn("findById");

        Statement statement = mock(Statement.class);
        when(statement.toString()).thenReturn("SELECT * FROM table WHERE id = 1");
        when(query.getStatement()).thenReturn(statement);

        // Access private method using reflection
        Method method = JPARepositoryAnalyzer.class.getDeclaredMethod("writeQueryToCsv", RepositoryQuery.class,
                PrintWriter.class);
        method.setAccessible(true);

        StringWriter stringWriter = new StringWriter();
        PrintWriter printWriter = new PrintWriter(stringWriter);

        // Execute
        method.invoke(analyzer, query, printWriter);

        // Verify
        String output = stringWriter.toString().trim();
        assertEquals("com.example.Repo,findById,SELECT * FROM table WHERE id = 1", output);
    }

    @Test
    void testWriteQueryToCsv_Escaping() throws Exception {
        // Prepare data with newlines and commas
        RepositoryQuery query = mock(RepositoryQuery.class);
        when(query.getRepositoryClassName()).thenReturn("com.example.Repo");
        when(query.getMethodName()).thenReturn("complexQuery");

        Statement statement = mock(Statement.class);
        when(statement.toString()).thenReturn("SELECT *\nFROM table\nWHERE name = 'O,Reilly'");
        when(query.getStatement()).thenReturn(statement);

        // Access private method
        Method method = JPARepositoryAnalyzer.class.getDeclaredMethod("writeQueryToCsv", RepositoryQuery.class,
                PrintWriter.class);
        method.setAccessible(true);

        StringWriter stringWriter = new StringWriter();
        PrintWriter printWriter = new PrintWriter(stringWriter);

        // Execute
        method.invoke(analyzer, query, printWriter);

        // Verify
        // Expected: Newlines -> \t, Comma in SQL -> Quotes around field
        // SQL becomes: SELECT *\tFROM table\tWHERE name = 'O,Reilly'
        // Since it contains a comma, it must be quoted: "SELECT *\tFROM table\tWHERE
        // name = 'O,Reilly'"
        String expectedSql = "\"SELECT *\tFROM table\tWHERE name = 'O,Reilly'\"";
        String expected = "com.example.Repo,complexQuery," + expectedSql;

        String output = stringWriter.toString().trim();
        assertEquals(expected, output);
    }
}
