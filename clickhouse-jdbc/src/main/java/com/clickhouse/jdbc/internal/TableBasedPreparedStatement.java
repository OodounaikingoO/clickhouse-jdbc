package com.clickhouse.jdbc.internal;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.Date;
import java.sql.ParameterMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import com.clickhouse.client.ClickHouseRequest;
import com.clickhouse.client.ClickHouseResponse;
import com.clickhouse.client.ClickHouseUtils;
import com.clickhouse.client.data.ClickHouseExternalTable;
import com.clickhouse.client.logging.Logger;
import com.clickhouse.client.logging.LoggerFactory;
import com.clickhouse.jdbc.ClickHousePreparedStatement;
import com.clickhouse.jdbc.SqlExceptionUtils;
import com.clickhouse.jdbc.parser.ClickHouseSqlStatement;

public class TableBasedPreparedStatement extends ClickHouseStatementImpl implements ClickHousePreparedStatement {
    private static final Logger log = LoggerFactory.getLogger(TableBasedPreparedStatement.class);

    private static final String ERROR_SET_TABLE = "Please use setObject(ClickHouseExternalTable) method instead";

    private final ClickHouseSqlStatement parsedStmt;
    private final List<String> tables;
    private final ClickHouseExternalTable[] values;

    private final List<List<ClickHouseExternalTable>> batch;

    protected TableBasedPreparedStatement(ClickHouseConnectionImpl connection, ClickHouseRequest<?> request,
            ClickHouseSqlStatement parsedStmt, int resultSetType, int resultSetConcurrency,
            int resultSetHoldability)
            throws SQLException {
        super(connection, request, resultSetType, resultSetConcurrency, resultSetHoldability);

        Set<String> set = parsedStmt != null ? parsedStmt.getTempTables() : null;
        if (set == null) {
            throw SqlExceptionUtils.clientError("Non-null table list is required");
        }

        this.parsedStmt = parsedStmt;
        int size = set.size();
        this.tables = new ArrayList<>(size);
        this.tables.addAll(set);
        values = new ClickHouseExternalTable[size];
        batch = new LinkedList<>();
    }

    protected void ensureParams() throws SQLException {
        List<String> list = new ArrayList<>();
        for (int i = 0, len = values.length; i < len; i++) {
            if (values[i] == null) {
                list.add(tables.get(i));
            }
        }

        if (!list.isEmpty()) {
            throw SqlExceptionUtils.clientError(ClickHouseUtils.format("Missing table(s): %s", list));
        }
    }

    protected String getSql() {
        // why? because request can be modified so it might not always same as
        // parsedStmt.getSQL()
        return getRequest().getStatements(false).get(0);
    }

    protected int toArrayIndex(int parameterIndex) throws SQLException {
        if (parameterIndex < 1 || parameterIndex > values.length) {
            throw SqlExceptionUtils.clientError(ClickHouseUtils
                    .format("Parameter index must between 1 and %d but we got %d", values.length, parameterIndex));
        }

        return parameterIndex - 1;
    }

    @Override
    public ResultSet executeQuery() throws SQLException {
        ensureParams();

        ClickHouseSqlStatement stmt = new ClickHouseSqlStatement(getSql());
        return updateResult(parsedStmt, executeStatement(stmt, null, Arrays.asList(values), null));
    }

    @Override
    public int executeUpdate() throws SQLException {
        ensureParams();

        try (ClickHouseResponse r = executeStatement(getSql(), null, Arrays.asList(values), null)) {
            updateResult(parsedStmt, r);
            return getUpdateCount();
        }
    }

    @Override
    public void setByte(int parameterIndex, byte x) throws SQLException {
        throw SqlExceptionUtils.clientError(ERROR_SET_TABLE);
    }

    @Override
    public void setShort(int parameterIndex, short x) throws SQLException {
        throw SqlExceptionUtils.clientError(ERROR_SET_TABLE);
    }

    @Override
    public void setInt(int parameterIndex, int x) throws SQLException {
        throw SqlExceptionUtils.clientError(ERROR_SET_TABLE);
    }

    @Override
    public void setLong(int parameterIndex, long x) throws SQLException {
        throw SqlExceptionUtils.clientError(ERROR_SET_TABLE);
    }

    @Override
    public void setFloat(int parameterIndex, float x) throws SQLException {
        throw SqlExceptionUtils.clientError(ERROR_SET_TABLE);
    }

    @Override
    public void setDouble(int parameterIndex, double x) throws SQLException {
        throw SqlExceptionUtils.clientError(ERROR_SET_TABLE);
    }

    @Override
    public void setBigDecimal(int parameterIndex, BigDecimal x) throws SQLException {
        throw SqlExceptionUtils.clientError(ERROR_SET_TABLE);
    }

    @Override
    public void setString(int parameterIndex, String x) throws SQLException {
        throw SqlExceptionUtils.clientError(ERROR_SET_TABLE);
    }

    @Override
    public void setBytes(int parameterIndex, byte[] x) throws SQLException {
        throw SqlExceptionUtils.clientError(ERROR_SET_TABLE);
    }

    @Override
    public void clearParameters() throws SQLException {
        ensureOpen();

        for (int i = 0, len = values.length; i < len; i++) {
            values[i] = null;
        }
    }

    @Override
    public void setObject(int parameterIndex, Object x) throws SQLException {
        ensureOpen();

        if (x instanceof ClickHouseExternalTable) {
            int idx = toArrayIndex(parameterIndex);
            values[idx] = (ClickHouseExternalTable) x;
        } else {
            throw SqlExceptionUtils.clientError("Only ClickHouseExternalTable is allowed");
        }
    }

    @Override
    public boolean execute() throws SQLException {
        ensureParams();

        ClickHouseSqlStatement stmt = new ClickHouseSqlStatement(getSql());
        ClickHouseResponse r = executeStatement(stmt, null, Arrays.asList(values), null);
        return updateResult(parsedStmt, r) != null;
    }

    @Override
    public void addBatch(String sql) throws SQLException {
        ensureOpen();

        throw SqlExceptionUtils
                .unsupportedError("addBatch(String) cannot be called in PreparedStatement or CallableStatement!");
    }

    @Override
    public void addBatch() throws SQLException {
        ensureOpen();

        ensureParams();
        List<ClickHouseExternalTable> list = new ArrayList<>(values.length);
        for (ClickHouseExternalTable v : values) {
            list.add(v);
        }
        batch.add(Collections.unmodifiableList(list));
        clearParameters();
    }

    @Override
    public int[] executeBatch() throws SQLException {
        ensureOpen();

        boolean continueOnError = getConnection().getJdbcConfig().isContinueBatchOnError();
        int len = batch.size();
        int[] results = new int[len];
        int counter = 0;
        try {
            String sql = getSql();
            for (List<ClickHouseExternalTable> list : batch) {
                try (ClickHouseResponse r = executeStatement(sql, null, list, null)) {
                    updateResult(parsedStmt, r);
                    int rows = getUpdateCount();
                    results[counter] = rows > 0 ? rows : 0;
                } catch (Exception e) {
                    if (!continueOnError) {
                        throw SqlExceptionUtils.handle(e);
                    }

                    results[counter] = EXECUTE_FAILED;
                    log.error("Failed to execute batch insert at %d of %d", counter + 1, len, e);
                }
                counter++;
            }
        } finally {
            clearBatch();
        }

        return results;
    }

    @Override
    public void clearBatch() throws SQLException {
        ensureOpen();

        this.batch.clear();
    }

    @Override
    public void setArray(int parameterIndex, Array x) throws SQLException {
        throw SqlExceptionUtils.clientError(ERROR_SET_TABLE);
    }

    @Override
    public void setDate(int parameterIndex, Date x, Calendar cal) throws SQLException {
        throw SqlExceptionUtils.clientError(ERROR_SET_TABLE);
    }

    @Override
    public void setTime(int parameterIndex, Time x, Calendar cal) throws SQLException {
        throw SqlExceptionUtils.clientError(ERROR_SET_TABLE);
    }

    @Override
    public void setTimestamp(int parameterIndex, Timestamp x, Calendar cal) throws SQLException {
        throw SqlExceptionUtils.clientError(ERROR_SET_TABLE);
    }

    @Override
    public void setNull(int parameterIndex, int sqlType, String typeName) throws SQLException {
        throw SqlExceptionUtils.clientError(ERROR_SET_TABLE);
    }

    @Override
    public ParameterMetaData getParameterMetaData() throws SQLException {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public void setObject(int parameterIndex, Object x, int targetSqlType, int scaleOrLength) throws SQLException {
        setObject(parameterIndex, x);
    }
}
