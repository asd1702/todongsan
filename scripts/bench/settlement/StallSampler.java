import java.nio.file.*;
import java.io.*;
import java.sql.*;
import java.util.*;

/** Read-only 100 ms sampling; prepare/restore only change Performance Schema instrumentation. */
public class StallSampler {
    private static final String FILTER = "NAME LIKE 'wait/io/file/%' OR NAME LIKE 'wait/synch/%/sql/MYSQL_BIN_LOG%'"
            + " OR NAME LIKE 'wait/synch/%/innodb/log%' OR NAME LIKE 'wait/synch/%/innodb/fil%'"
            + " OR NAME LIKE 'stage/sql/%'";
    private static String clean(String s) { return s == null ? "" : s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' '); }
    private static String quote(String s) { return "'" + s.replace("'", "''") + "'"; }
    private static String query(Connection c, String sql) throws Exception {
        StringBuilder out = new StringBuilder();
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
                    if (i > 1) out.append('\t');
                    out.append(clean(rs.getString(i)));
                }
                out.append('\n');
            }
        }
        return out.toString();
    }
    private static void snapshot(Connection c, Path dir, String name) throws Exception {
        Files.writeString(dir.resolve(name + "-settings.tsv"), query(c,
                "SELECT VARIABLE_NAME, VARIABLE_VALUE FROM performance_schema.global_variables WHERE VARIABLE_NAME IN "
                + "('version','sync_binlog','innodb_flush_log_at_trx_commit','innodb_extend_and_initialize',"
                + "'innodb_buffer_pool_size','innodb_redo_log_capacity','max_binlog_size','binlog_group_commit_sync_delay',"
                + "'performance_schema_events_waits_history_long_size','performance_schema_events_statements_history_long_size')"));
        Files.writeString(dir.resolve(name + "-tablespaces.tsv"), query(c,
                "SELECT NAME, FILE_SIZE, ALLOCATED_SIZE FROM information_schema.INNODB_TABLESPACES "
                + "WHERE NAME IN ('memberpoint/point_history','memberpoint/point_outbox_event','memberpoint/member')"));
        Files.writeString(dir.resolve(name + "-binlogs.tsv"), query(c, "SHOW BINARY LOGS"));
        Files.writeString(dir.resolve(name + "-threads.tsv"), query(c,
                "SELECT THREAD_ID,THREAD_OS_ID,NAME,TYPE,PROCESSLIST_ID,PROCESSLIST_USER,INSTRUMENTED,HISTORY FROM performance_schema.threads"));
        Files.writeString(dir.resolve(name + "-timers.tsv"), query(c, "SELECT * FROM performance_schema.performance_timers"));
        Files.writeString(dir.resolve(name + "-files.tsv"), query(c,
                "SELECT FILE_NAME, EVENT_NAME, COUNT_READ, SUM_TIMER_READ, COUNT_WRITE, SUM_TIMER_WRITE, COUNT_MISC, "
                + "SUM_TIMER_MISC, MAX_TIMER_MISC, MAX_TIMER_WRITE, MAX_TIMER_READ FROM performance_schema.file_summary_by_instance WHERE EVENT_NAME LIKE 'wait/io/file/%'"));
    }
    public static void main(String[] args) throws Exception {
        String mode = args[0];
        Path dir = Path.of(args[1]);
        Files.createDirectories(dir);
        Class.forName("com.mysql.cj.jdbc.Driver");
        try (Connection c = DriverManager.getConnection(
                "jdbc:mysql://127.0.0.1:3307/memberpoint?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Seoul",
                "root", System.getenv("MEMBERPOINT_MYSQL_ROOT_PASSWORD"))) {
            if (mode.equals("prepare")) {
                Path restore = dir.resolve("restore.sql");
                if (Files.exists(restore)) throw new IllegalStateException("Already prepared: " + restore);
                StringBuilder sql = new StringBuilder();
                try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(
                        "SELECT NAME, ENABLED, TIMED FROM performance_schema.setup_instruments WHERE " + FILTER)) {
                    while (rs.next()) sql.append("UPDATE performance_schema.setup_instruments SET ENABLED=")
                            .append(quote(rs.getString(2))).append(", TIMED=").append(quote(rs.getString(3)))
                            .append(" WHERE NAME=").append(quote(rs.getString(1))).append(";\n");
                }
                sql.append(query(c, "SELECT CONCAT('UPDATE performance_schema.setup_consumers SET ENABLED=', "
                        + "QUOTE(ENABLED), ' WHERE NAME=', QUOTE(NAME), ';') FROM performance_schema.setup_consumers "
                        + "WHERE NAME IN ('events_waits_current','events_waits_history','events_waits_history_long',"
                        + "'events_statements_history_long','events_stages_current')"));
                Files.writeString(restore, sql);
                snapshot(c, dir, "initial");
                try (Statement st = c.createStatement()) {
                    st.executeUpdate("UPDATE performance_schema.setup_instruments SET ENABLED='YES', TIMED='YES' WHERE " + FILTER);
                    st.executeUpdate("UPDATE performance_schema.setup_consumers SET ENABLED='YES' WHERE NAME IN "
                            + "('events_waits_current','events_waits_history','events_waits_history_long',"
                            + "'events_statements_history_long','events_stages_current')");
                }
                System.out.println("Instrumentation enabled; restore saved to " + restore);
                return;
            }
            if (mode.equals("restore")) {
                try (Statement st = c.createStatement()) {
                    for (String line : Files.readAllLines(dir.resolve("restore.sql")))
                        if (!line.isBlank()) st.executeUpdate(line);
                }
                System.out.println("Instrumentation restored");
                return;
            }
            snapshot(c, dir, "before");
            long ownThread = Long.parseLong(query(c, "SELECT THREAD_ID FROM performance_schema.threads WHERE PROCESSLIST_ID=CONNECTION_ID()").trim());
            int seconds = args.length > 2 ? Integer.parseInt(args[2]) : 110;
            int interval = args.length > 3 ? Integer.parseInt(args[3]) : 100;
            boolean kernel = "1".equals(System.getenv("STALL_KERNEL_TRACE"));
            long lastTrigger = 0;
            Map<String, String> queries = new LinkedHashMap<>();
            queries.put("clock", "SELECT CAST(UNIX_TIMESTAMP(NOW(6))*1000000 AS UNSIGNED), TIMER_START FROM performance_schema.events_statements_current WHERE THREAD_ID=" + ownThread);
            queries.put("current", "SELECT t.THREAD_ID,t.PROCESSLIST_ID,t.PROCESSLIST_USER,t.PROCESSLIST_STATE,s.EVENT_NAME,s.TIMER_START,s.TIMER_WAIT,"
                    + "w.EVENT_NAME,w.TIMER_START,w.TIMER_WAIT,w.OPERATION,w.OBJECT_NAME,LEFT(s.SQL_TEXT,500),w.TIMER_END,w.END_EVENT_ID,"
                    + "t.NAME,t.THREAD_OS_ID,s.EVENT_ID,s.END_EVENT_ID,stg.EVENT_NAME,stg.TIMER_WAIT "
                    + "FROM performance_schema.threads t LEFT JOIN performance_schema.events_statements_current s ON s.THREAD_ID=t.THREAD_ID "
                    + "LEFT JOIN performance_schema.events_waits_current w ON w.THREAD_ID=t.THREAD_ID "
                    + "LEFT JOIN performance_schema.events_stages_current stg ON stg.THREAD_ID=t.THREAD_ID "
                    + "WHERE t.THREAD_ID<>" + ownThread + " AND ((t.PROCESSLIST_USER='memberpoint_user' AND t.PROCESSLIST_COMMAND<>'Sleep') OR "
                    + "(t.TYPE='BACKGROUND' AND w.END_EVENT_ID IS NULL AND w.EVENT_NAME IS NOT NULL))");
            queries.put("waits", "SELECT THREAD_ID,EVENT_ID,EVENT_NAME,TIMER_START,TIMER_END,TIMER_WAIT,OPERATION,OBJECT_NAME,NESTING_EVENT_ID,NESTING_EVENT_TYPE "
                    + "FROM performance_schema.events_waits_history_long WHERE TIMER_WAIT>=10000000000 AND EVENT_NAME<>'idle'");
            queries.put("statements", "SELECT s.THREAD_ID,s.EVENT_ID,s.EVENT_NAME,s.TIMER_START,s.TIMER_END,s.TIMER_WAIT,s.LOCK_TIME,s.ROWS_AFFECTED,LEFT(s.SQL_TEXT,500) "
                    + "FROM performance_schema.events_statements_history_long s JOIN performance_schema.threads t ON t.THREAD_ID=s.THREAD_ID "
                    + "WHERE t.PROCESSLIST_USER='memberpoint_user' AND s.TIMER_WAIT>=50000000000");
            queries.put("tablespaces", "SELECT NAME,FILE_SIZE,ALLOCATED_SIZE FROM information_schema.INNODB_TABLESPACES "
                    + "WHERE NAME IN ('memberpoint/point_history','memberpoint/point_outbox_event')");
            queries.put("files", "SELECT FILE_NAME,EVENT_NAME,COUNT_WRITE,SUM_TIMER_WRITE,MAX_TIMER_WRITE,COUNT_MISC,SUM_TIMER_MISC,MAX_TIMER_MISC "
                    + "FROM performance_schema.file_summary_by_instance WHERE FILE_NAME LIKE '%mysql-bin.%' OR FILE_NAME LIKE '%point_history.ibd' "
                    + "OR FILE_NAME LIKE '%point_outbox_event.ibd' OR FILE_NAME LIKE '%#ib_redo%'");
            queries.put("status", "SELECT VARIABLE_NAME,VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME IN "
                    + "('Innodb_row_lock_waits','Innodb_row_lock_time','Innodb_buffer_pool_wait_free','Innodb_log_waits',"
                    + "'Innodb_data_pending_fsyncs','Innodb_data_pending_writes','Innodb_os_log_pending_fsyncs',"
                    + "'Innodb_os_log_pending_writes','Innodb_buffer_pool_pages_dirty','Innodb_redo_log_current_lsn','Innodb_redo_log_checkpoint_lsn',"
                    + "'Innodb_redo_log_flushed_to_disk_lsn','Threads_running')");
            Map<String, BufferedWriter> writers = new LinkedHashMap<>();
            Map<String, Set<String>> seen = new HashMap<>();
            for (String key : queries.keySet()) {
                writers.put(key, Files.newBufferedWriter(dir.resolve(key + ".tsv")));
                seen.put(key, new HashSet<>());
            }
            long end = System.nanoTime() + seconds * 1_000_000_000L;
            int samples = 0;
            try {
                while (System.nanoTime() < end && !Files.exists(dir.resolve("stop"))) {
                    long cycle = System.nanoTime();
                    for (var entry : queries.entrySet()) {
                        if (entry.getKey().equals("tablespaces") && samples % Math.max(1, 1000 / interval) != 0) continue;
                        long before = System.currentTimeMillis();
                        String data = query(c, entry.getValue());
                        long after = System.currentTimeMillis();
                        if (kernel && entry.getKey().equals("current") && after - lastTrigger > 1000) {
                            int slowCommits = 0;
                            for (String line : data.split("\n")) {
                                String[] fields = line.split("\t", -1);
                                if (fields.length > 18 && fields[4].equals("statement/sql/commit") && fields[18].isEmpty()
                                        && !fields[6].isEmpty() && Double.parseDouble(fields[6]) >= 100_000_000_000L
                                        && Double.parseDouble(fields[6]) < 10_000_000_000_000L) slowCommits++;
                            }
                            if (slowCommits >= 3) {
                                lastTrigger = after;
                                Files.writeString(dir.resolve("triggers.tsv"), after + "\t" + slowCommits + "\n",
                                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                                new ProcessBuilder("docker", "exec", "codex-mysql-stall-trace", "sh", "/tmp/capture.sh", "snapshot")
                                        .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(dir.resolve("trigger.log").toFile())).start();
                            }
                        }
                        for (String line : data.split("\n")) {
                            if (line.isBlank()) continue;
                            if ((entry.getKey().equals("waits") || entry.getKey().equals("statements"))
                                    && !seen.get(entry.getKey()).add(line.split("\t", 3)[0] + ":" + line.split("\t", 3)[1])) continue;
                            writers.get(entry.getKey()).write(before + "\t" + after + "\t" + line + "\n");
                        }
                    }
                    for (BufferedWriter w : writers.values()) w.flush();
                    samples++;
                    long remaining = interval * 1_000_000L - (System.nanoTime() - cycle);
                    if (remaining > 0) Thread.sleep(remaining / 1_000_000L);
                }
            } finally {
                for (BufferedWriter w : writers.values()) w.close();
                snapshot(c, dir, "after");
                Files.writeString(dir.resolve("sampler-summary.txt"), "samples=" + samples + "\ninterval_target_ms=" + interval + "\n");
            }
            System.out.println("Captured " + samples + " samples in " + dir);
        }
    }
}
