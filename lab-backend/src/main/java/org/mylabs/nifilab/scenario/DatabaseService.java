package org.mylabs.nifilab.scenario;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import org.mylabs.nifilab.LabConfig;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the lab database so the UI can show what a flow wrote there (scenario.yaml {@code database}). Plain JDBC:
 * a handful of small queries does not need a connection pool.
 */
@ApplicationScoped
public class DatabaseService {

    private static final Logger LOG = Logger.getLogger(DatabaseService.class);

    @Inject
    LabConfig config;

    @Inject
    ScenarioRegistry registry;

    /** Result of the scenario's query, or the error that prevented it (e.g. the database is not up yet). */
    public record TableView(String title, List<String> columns, List<List<String>> rows, String error) {
    }

    public TableView query(ScenarioDefinition.DatabaseView view) {
        try (Connection c = connect(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(view.query())) {
            ResultSetMetaData meta = rs.getMetaData();
            List<String> columns = new ArrayList<>();
            for (int i = 1; i <= meta.getColumnCount(); i++) {
                columns.add(meta.getColumnLabel(i));
            }
            List<List<String>> rows = new ArrayList<>();
            while (rs.next()) {
                List<String> row = new ArrayList<>();
                for (int i = 1; i <= columns.size(); i++) {
                    row.add(rs.getString(i));
                }
                rows.add(row);
            }
            return new TableView(view.title(), columns, rows, null);
        } catch (SQLException e) {
            return new TableView(view.title(), List.of(), List.of(), e.getMessage());
        }
    }

    void onReset(@Observes ScenarioReset reset) {
        registry.find(reset.scenarioId())
                .map(ScenarioDefinition::database)
                .filter(db -> db.reset() != null)
                .ifPresent(db -> {
                    try (Connection c = connect(); Statement st = c.createStatement()) {
                        st.execute(db.reset());
                    } catch (SQLException e) {
                        LOG.warnf("Could not reset the database of scenario %s: %s", reset.scenarioId(), e.getMessage());
                    }
                });
    }

    private Connection connect() throws SQLException {
        DriverManager.setLoginTimeout(3);
        return DriverManager.getConnection(config.database().url(), config.database().user(), config.database().password());
    }
}
