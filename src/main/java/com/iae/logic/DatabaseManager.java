package com.iae.logic;

import com.iae.model.Configuration;
import com.iae.model.EvaluationResult;
import com.iae.model.ExecutionResult;
import com.iae.model.Project;
import com.iae.model.Status;
import com.iae.model.TestCase;

import java.sql.*;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Singleton that owns the single SQLite JDBC connection for the application's
 * lifetime.
 *
 * <p>
 * On first connection the database file {@code iae.db} is created in the
 * application working directory and the three tables
 * ({@code PROJECTS}, {@code TESTCASES}, {@code RESULTS}) are initialised with
 * {@code CREATE TABLE IF NOT EXISTS}, so the call is safe on every launch.
 *
 * <p>
 * All mutating operations are wrapped in explicit transactions; callers
 * receive {@link SQLException} cleanly so the UI layer can handle them.
 */
public class DatabaseManager {

    // ── Singleton ─────────────────────────────────────────────────────────────

    private static DatabaseManager instance;

    /** @return the single application-wide instance */
    public static DatabaseManager getInstance() {
        if (instance == null) {
            instance = new DatabaseManager();
        }
        return instance;
    }

    protected DatabaseManager() {
    }

    // ── Connection ────────────────────────────────────────────────────────────

    // Defaults to iae.db in the working directory. Tests override this via the
    // "iae.db.url" system property (see surefire config) so they never touch the
    // real application database.
    private static final String DB_URL =
            System.getProperty("iae.db.url", "jdbc:sqlite:iae.db");

    private Connection connection;

    /**
     * Opens (or re-opens) the SQLite connection and initialises the schema.
     * Safe to call multiple times.
     */
    public synchronized void connect() throws SQLException {
        if (connection == null || connection.isClosed()) {
            connection = DriverManager.getConnection(DB_URL);
            connection.setAutoCommit(true);
            initSchema(connection);
        }
    }

    /**
     * Closes the connection. Subsequent calls to any data method will re-open it.
     */
    public synchronized void close() throws SQLException {
        if (connection != null && !connection.isClosed()) {
            connection.close();
        }
    }

    /**
     * Drops all tables and re-creates them — intended for testing only.
     * Closes and re-opens the connection so the in-memory state is fully reset.
     */
    public synchronized void reset() throws SQLException {
        connect();
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS RESULTS");
            stmt.execute("DROP TABLE IF EXISTS TESTCASES");
            stmt.execute("DROP TABLE IF EXISTS PROJECTS");
            stmt.execute("DROP TABLE IF EXISTS CONFIGURATIONS");
        }
        initSchema(connection);
    }

    /**
     * Returns the live connection, opening it first if necessary.
     *
     * @return a non-null, open {@link Connection}
     */
    public synchronized Connection getConnection() throws SQLException {
        connect();
        return connection;
    }

    // ── Schema ────────────────────────────────────────────────────────────────

    private void initSchema(Connection conn) throws SQLException {
        createTables(conn);
        applyMigrations(conn);
    }

    private void createTables(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {

            // CONFIGURATIONS
            stmt.execute(
                    "CREATE TABLE IF NOT EXISTS CONFIGURATIONS (" +
                            "  id                INTEGER PRIMARY KEY AUTOINCREMENT," +
                            "  name              TEXT    NOT NULL," +
                            "  language          TEXT," +
                            "  source_file       TEXT," +
                            "  needs_compilation INTEGER NOT NULL DEFAULT 0," +
                            "  compile_command   TEXT," +
                            "  compile_args      TEXT," +
                            "  run_command       TEXT," +
                            "  run_args          TEXT" +
                            ")");

            // PROJECTS
            stmt.execute(
                    "CREATE TABLE IF NOT EXISTS PROJECTS (" +
                            "  id               INTEGER PRIMARY KEY AUTOINCREMENT," +
                            "  name             TEXT    NOT NULL," +
                            "  configuration_id TEXT," +
                            "  submissions_dir  TEXT," +
                            "  created_at       DATETIME," +
                            "  last_run_at      DATETIME" +
                            ")");

            // TESTCASES
            stmt.execute(
                    "CREATE TABLE IF NOT EXISTS TESTCASES (" +
                            "  id               INTEGER PRIMARY KEY AUTOINCREMENT," +
                            "  project_id       INTEGER NOT NULL REFERENCES PROJECTS(id) ON DELETE CASCADE," +
                            "  name             TEXT," +
                            "  input_args       TEXT," +
                            "  expected_output  TEXT," +
                            "  created_at       DATETIME" +
                            ")");

            // RESULTS – new installations get the FK constraint directly
            stmt.execute(
                    "CREATE TABLE IF NOT EXISTS RESULTS (" +
                            "  id              INTEGER PRIMARY KEY AUTOINCREMENT," +
                            "  project_id      INTEGER  REFERENCES PROJECTS(id) ON DELETE CASCADE," +
                            "  test_case_id    INTEGER," +
                            "  student_id      TEXT    NOT NULL," +
                            "  status          TEXT    NOT NULL," +
                            "  stdout          TEXT," +
                            "  stderr          TEXT," +
                            "  error_message   TEXT," +
                            "  duration_ms     LONG," +
                            "  run_at          DATETIME" +
                            ")");
        }
    }

    // Runs pending schema upgrades keyed by PRAGMA user_version.
    private void applyMigrations(Connection conn) throws SQLException {
        int version;
        try (ResultSet rs = conn.createStatement().executeQuery("PRAGMA user_version")) {
            version = rs.getInt(1);
        }
        if (version < 1) {
            migrateV0toV1(conn);
            conn.createStatement().execute("PRAGMA user_version = 1");
        }
    }

    // v0 → v1: rebuild RESULTS with ON DELETE CASCADE on project_id.
    // SQLite does not support ALTER TABLE ADD CONSTRAINT, so the table is
    // recreated and data copied before the old table is dropped.
    private void migrateV0toV1(Connection conn) throws SQLException {
        conn.setAutoCommit(false);
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA foreign_keys = OFF");
            stmt.execute(
                    "CREATE TABLE RESULTS_v1 (" +
                            "  id              INTEGER PRIMARY KEY AUTOINCREMENT," +
                            "  project_id      INTEGER  REFERENCES PROJECTS(id) ON DELETE CASCADE," +
                            "  test_case_id    INTEGER," +
                            "  student_id      TEXT    NOT NULL," +
                            "  status          TEXT    NOT NULL," +
                            "  stdout          TEXT," +
                            "  stderr          TEXT," +
                            "  error_message   TEXT," +
                            "  duration_ms     LONG," +
                            "  run_at          DATETIME" +
                            ")");
            stmt.execute("INSERT INTO RESULTS_v1 SELECT * FROM RESULTS");
            stmt.execute("DROP TABLE RESULTS");
            stmt.execute("ALTER TABLE RESULTS_v1 RENAME TO RESULTS");
            conn.commit();
            stmt.execute("PRAGMA foreign_keys = ON");
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(true);
        }
    }

    // ── Configuration CRUD ────────────────────────────────────────────────────

    /**
     * Inserts a new configuration (if {@code config.getId() == 0}) or updates
     * the existing row. Sets the generated id on the config object after insert.
     *
     * @throws SQLException on any DB error
     */
    public synchronized void saveConfiguration(Configuration config) throws SQLException {
        connect();
        if (config.getId() == 0) {
            insertConfiguration(config);
        } else {
            updateConfiguration(config);
        }
    }

    private void insertConfiguration(Configuration config) throws SQLException {
        String sql =
            "INSERT INTO CONFIGURATIONS(name, language, source_file, needs_compilation," +
            " compile_command, compile_args, run_command, run_args)" +
            " VALUES(?, ?, ?, ?, ?, ?, ?, ?)";
        connection.setAutoCommit(false);
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            bindConfigFields(ps, config);
            ps.executeUpdate();
            try (Statement s = connection.createStatement();
                 ResultSet keys = s.executeQuery("SELECT last_insert_rowid()")) {
                if (keys.next()) config.setId(keys.getInt(1));
            }
            connection.commit();
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    private void updateConfiguration(Configuration config) throws SQLException {
        String sql = "UPDATE CONFIGURATIONS SET name=?, language=?, source_file=?," +
                " needs_compilation=?, compile_command=?, compile_args=?," +
                " run_command=?, run_args=? WHERE id=?";
        connection.setAutoCommit(false);
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            bindConfigFields(ps, config);
            ps.setInt(9, config.getId());
            ps.executeUpdate();
            connection.commit();
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    /**
     * Binds all editable fields of a {@link Configuration} onto a PreparedStatement
     * (params 1-8).
     */
    private static void bindConfigFields(PreparedStatement ps, Configuration c) throws SQLException {
        ps.setString(1, c.getName());
        ps.setString(2, c.getLanguage());
        ps.setString(3, c.getSourceFile());
        ps.setInt(4, c.isNeedsCompilation() ? 1 : 0);
        ps.setString(5, c.getCompileCommand());
        ps.setString(6, c.getCompileArgs());
        ps.setString(7, c.getRunCommand());
        ps.setString(8, c.getRunArgs());
    }

    /**
     * Returns all persisted configurations, ordered by id.
     *
     * @throws SQLException on any DB error
     */
    public synchronized List<Configuration> getAllConfigurations() throws SQLException {
        connect();
        List<Configuration> list = new ArrayList<>();
        String sql = "SELECT id, name, language, source_file, needs_compilation," +
                " compile_command, compile_args, run_command, run_args" +
                " FROM CONFIGURATIONS ORDER BY id";
        try (PreparedStatement ps = connection.prepareStatement(sql);
                ResultSet rs = ps.executeQuery()) {
            while (rs.next())
                list.add(mapConfiguration(rs));
        }
        return list;
    }

    /**
     * Deletes the configuration row with the given id.
     *
     * @throws SQLException on any DB error
     */
    public synchronized void deleteConfiguration(int id) throws SQLException {
        connect();
        connection.setAutoCommit(false);
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM CONFIGURATIONS WHERE id=?")) {
            ps.setInt(1, id);
            ps.executeUpdate();
            connection.commit();
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    private Configuration mapConfiguration(ResultSet rs) throws SQLException {
        Configuration c = new Configuration();
        c.setId(rs.getInt("id"));
        c.setName(rs.getString("name"));
        c.setLanguage(rs.getString("language"));
        c.setSourceFile(rs.getString("source_file"));
        c.setNeedsCompilation(rs.getInt("needs_compilation") == 1);
        c.setCompileCommand(rs.getString("compile_command"));
        c.setCompileArgs(rs.getString("compile_args"));
        c.setRunCommand(rs.getString("run_command"));
        c.setRunArgs(rs.getString("run_args"));
        return c;
    }

    // ── Project CRUD ──────────────────────────────────────────────────────────

    /**
     * Inserts a new project (if {@code project.getId() == 0}) or updates the
     * existing row. Sets the generated id on the project object after insert.
     *
     * @throws SQLException on any DB error
     */
    public synchronized void saveProject(Project project) throws SQLException {
        connect();
        if (project.getId() == 0) {
            insertProject(project);
        } else {
            updateProject(project);
        }
    }

    private void insertProject(Project project) throws SQLException {
        String sql = "INSERT INTO PROJECTS(name, configuration_id, submissions_dir, created_at, last_run_at)" +
                " VALUES(?, ?, ?, ?, ?)";
        connection.setAutoCommit(false);
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, project.getName());
            ps.setString(2, project.getConfigurationId());
            ps.setString(3, project.getSubmissionsDirectory());
            ps.setString(4, toIso(project.getCreatedAt()));
            ps.setString(5, toIso(project.getLastRunAt()));
            ps.executeUpdate();
            try (Statement s = connection.createStatement();
                    ResultSet keys = s.executeQuery("SELECT last_insert_rowid()")) {
                if (keys.next()) {
                    project.setId(keys.getInt(1));
                }
            }
            connection.commit();
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    private void updateProject(Project project) throws SQLException {
        String sql = "UPDATE PROJECTS SET name=?, configuration_id=?, submissions_dir=?," +
                " created_at=?, last_run_at=? WHERE id=?";
        connection.setAutoCommit(false);
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, project.getName());
            ps.setString(2, project.getConfigurationId());
            ps.setString(3, project.getSubmissionsDirectory());
            ps.setString(4, toIso(project.getCreatedAt()));
            ps.setString(5, toIso(project.getLastRunAt()));
            ps.setInt(6, project.getId());
            ps.executeUpdate();
            connection.commit();
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    /**
     * Loads a single project by primary key, including its test cases and results.
     *
     * @param id the project id
     * @return the reconstructed {@link Project}, or {@code null} if not found
     * @throws SQLException on any DB error
     */
    public synchronized Project loadProject(int id) throws SQLException {
        connect();
        String sql = "SELECT id, name, configuration_id, submissions_dir, created_at, last_run_at" +
                " FROM PROJECTS WHERE id=?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next())
                    return null;
                Project p = mapProject(rs);
                loadTestCasesInto(p);
                loadResultsInto(p);
                return p;
            }
        }
    }

    /**
     * Returns all persisted projects, each populated with their test cases and
     * results.
     *
     * @throws SQLException on any DB error
     */
    public synchronized List<Project> getAllProjects() throws SQLException {
        connect();
        List<Project> projects = new ArrayList<>();
        String sql = "SELECT id, name, configuration_id, submissions_dir, created_at, last_run_at" +
                " FROM PROJECTS ORDER BY id";
        try (PreparedStatement ps = connection.prepareStatement(sql);
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Project p = mapProject(rs);
                loadTestCasesInto(p);
                loadResultsInto(p);
                projects.add(p);
            }
        }
        return projects;
    }

    /**
     * Deletes the project row with the given id (test cases are cascade-deleted
     * by the FK constraint).
     *
     * @throws SQLException on any DB error
     */
    public synchronized void deleteProject(int id) throws SQLException {
        connect();
        connection.setAutoCommit(false);
        try (PreparedStatement ps = connection.prepareStatement("DELETE FROM PROJECTS WHERE id=?")) {
            ps.setInt(1, id);
            ps.executeUpdate();
            connection.commit();
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    // ── TestCase CRUD ─────────────────────────────────────────────────────────

    /**
     * Replaces all test cases for the given project in a single transaction:
     * deletes existing rows then inserts the provided list.
     *
     * @param testCases list of test cases; each must have {@code projectId} set
     * @throws SQLException on any DB error
     */
    public synchronized void saveTestCasesForProject(List<TestCase> testCases) throws SQLException {
        if (testCases == null || testCases.isEmpty())
            return;
        int projectId = testCases.get(0).getProjectId();
        connect();
        connection.setAutoCommit(false);
        try {
            // Remove old rows
            try (PreparedStatement del = connection.prepareStatement(
                    "DELETE FROM TESTCASES WHERE project_id=?")) {
                del.setInt(1, projectId);
                del.executeUpdate();
            }
            // Insert new rows
            String ins = "INSERT INTO TESTCASES(project_id, name, input_args, expected_output, created_at)" +
                    " VALUES(?, ?, ?, ?, ?)";
            try (PreparedStatement ins_ps = connection.prepareStatement(ins)) {
                for (TestCase tc : testCases) {
                    ins_ps.setInt(1, tc.getProjectId());
                    ins_ps.setString(2, tc.getName());
                    ins_ps.setString(3, tc.getInputArgs());
                    ins_ps.setString(4, tc.getExpectedOutputFilePath());
                    ins_ps.setString(5, toIso(tc.getCreatedAt()));
                    ins_ps.executeUpdate();
                    try (Statement s = connection.createStatement();
                            ResultSet keys = s.executeQuery("SELECT last_insert_rowid()")) {
                        if (keys.next())
                            tc.setId(keys.getInt(1));
                    }
                }
            }
            connection.commit();
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    /**
     * Returns all test cases for the given project, ordered by id.
     *
     * @throws SQLException on any DB error
     */
    public synchronized List<TestCase> getTestCasesForProject(int projectId) throws SQLException {
        connect();
        List<TestCase> list = new ArrayList<>();
        String sql = "SELECT id, project_id, name, input_args, expected_output, created_at" +
                " FROM TESTCASES WHERE project_id=? ORDER BY id";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, projectId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(mapTestCase(rs));
                }
            }
        }
        return list;
    }

    // ── Result CRUD ───────────────────────────────────────────────────────────

    /**
     * Saves an evaluation result. The {@code project_id} column is set to 0.
     * Use {@link #saveResult(EvaluationResult, int)} to associate with a project.
     *
     * @param result the result to persist
     */
    public synchronized void saveResult(EvaluationResult result) {
        saveResult(result, 0);
    }

    /**
     * Saves an evaluation result associated with the given project.
     *
     * @param result    the result to persist
     * @param projectId the owning project's id (use 0 if unknown)
     */
    public synchronized void saveResult(EvaluationResult result, int projectId) {
        String stdout = result.getExecutionResults().stream()
                .map(ExecutionResult::getStdout)
                .filter(s -> s != null && !s.isBlank())
                .collect(java.util.stream.Collectors.joining("\n"));
        String stderr = result.getExecutionResults().stream()
                .map(ExecutionResult::getStderr)
                .filter(s -> s != null && !s.isBlank())
                .collect(java.util.stream.Collectors.joining("\n"));
        saveResult(result, projectId, 0,
                stdout.isEmpty() ? null : stdout,
                stderr.isEmpty() ? null : stderr);
    }

    public synchronized void saveResult(EvaluationResult result, int projectId,
                                        int testCaseId, String stdout, String stderr) {
        String sql = "INSERT INTO RESULTS(project_id, test_case_id, student_id, status," +
                " stdout, stderr, error_message, duration_ms, run_at)" +
                " VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try {
            connect();
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setInt(1, projectId);
                if (testCaseId > 0) ps.setInt(2, testCaseId);
                else ps.setNull(2, Types.INTEGER);
                ps.setString(3, result.getStudentId());
                ps.setString(4, result.getStatus() != null
                        ? result.getStatus().name()
                        : Status.SOURCE_MISSING.name());
                ps.setString(5, stdout);
                ps.setString(6, stderr);
                ps.setString(7, result.getErrorMessage());
                ps.setLong(8, result.getDurationMs());
                ps.setString(9, toIso(new Date()));
                ps.executeUpdate();
                try (Statement s = connection.createStatement();
                        ResultSet keys = s.executeQuery("SELECT last_insert_rowid()")) {
                    if (keys.next())
                        result.setId(keys.getInt(1));
                }
            }
        } catch (SQLException e) {
            System.err.println("DatabaseManager.saveResult failed: " + e.getMessage());
        }
    }

    /**
     * Returns all results stored for the given project, ordered by id.
     *
     * @param projectId the owning project's id
     * @return list of {@link EvaluationResult} (without execution/comparison
     *         sub-lists)
     */
    public synchronized List<EvaluationResult> getResultsForProject(int projectId) {
        List<EvaluationResult> results = new ArrayList<>();
        String sql = "SELECT id, student_id, status, error_message, duration_ms" +
                " FROM RESULTS WHERE project_id=? ORDER BY id";
        try {
            connect();
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setInt(1, projectId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        EvaluationResult r = new EvaluationResult(rs.getString("student_id"));
                        r.setId(rs.getInt("id"));
                        String statusStr = rs.getString("status");
                        if (statusStr != null) {
                            try {
                                r.setStatus(Status.valueOf(statusStr));
                            } catch (IllegalArgumentException ignored) {
                            }
                        }
                        r.setErrorMessage(rs.getString("error_message"));
                        r.setDurationMs(rs.getLong("duration_ms"));
                        results.add(r);
                    }
                }
            }
        } catch (SQLException e) {
            System.err.println("DatabaseManager.getResultsForProject failed: " + e.getMessage());
        }
        return results;
    }

    // ── Last-run info (for Open Project dialog) ───────────────────────────────

    /**
     * Returns a human-readable "last run" string for the given project, derived
     * from the {@code run_at} column of the most-recent RESULTS row.
     * Returns {@code "Never"} if no results exist for the project.
     * No schema changes are made — this works entirely within the existing table.
     *
     * @param projectId the project id to query
     * @return a date string like "2026-05-29 10:30:00", or "Never"
     */
    public synchronized String getLastRunInfo(int projectId) {
        String sql = "SELECT run_at FROM RESULTS WHERE project_id=? ORDER BY id DESC LIMIT 1";
        try {
            connect();
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setInt(1, projectId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        String runAt = rs.getString("run_at");
                        return (runAt != null && !runAt.isBlank()) ? runAt : "—";
                    }
                }
            }
        } catch (SQLException e) {
            System.err.println("DatabaseManager.getLastRunInfo failed: " + e.getMessage());
        }
        return "Never";
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private Project mapProject(ResultSet rs) throws SQLException {
        Project p = new Project();
        p.setId(rs.getInt("id"));
        p.setName(rs.getString("name"));
        p.setConfigurationId(rs.getString("configuration_id"));
        p.setSubmissionsDirectory(rs.getString("submissions_dir"));
        p.setCreatedAt(fromIso(rs.getString("created_at")));
        p.setLastRunAt(fromIso(rs.getString("last_run_at")));
        return p;
    }

    private TestCase mapTestCase(ResultSet rs) throws SQLException {
        TestCase tc = new TestCase();
        tc.setId(rs.getInt("id"));
        tc.setProjectId(rs.getInt("project_id"));
        tc.setName(rs.getString("name"));
        tc.setInputArgs(rs.getString("input_args"));
        tc.setExpectedOutputFilePath(rs.getString("expected_output"));
        tc.setCreatedAt(fromIso(rs.getString("created_at")));
        return tc;
    }

    private void loadTestCasesInto(Project p) throws SQLException {
        for (TestCase tc : getTestCasesForProject(p.getId())) {
            p.addTestCase(tc);
        }
    }

    private void loadResultsInto(Project p) {
        for (EvaluationResult r : getResultsForProject(p.getId())) {
            p.addResult(r);
        }
    }

    /** Converts a {@link Date} to an ISO-8601 string, or {@code null}. */
    private static String toIso(Date date) {
        return (date == null) ? null : new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(date);
    }

    /** Parses an ISO-8601 string to a {@link Date}, or returns {@code null}. */
    private static Date fromIso(String s) {
        if (s == null || s.isBlank())
            return null;
        try {
            return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse(s);
        } catch (java.text.ParseException e) {
            return null;
        }
    }
}
