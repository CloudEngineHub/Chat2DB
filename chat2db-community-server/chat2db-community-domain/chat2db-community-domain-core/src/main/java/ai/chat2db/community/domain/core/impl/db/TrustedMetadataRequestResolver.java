package ai.chat2db.community.domain.core.impl.db;

import java.util.List;
import java.util.Objects;
import java.sql.Connection;

import ai.chat2db.community.domain.api.model.metadata.Table;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.spi.IDbMetaData;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.model.request.TableMetadataRequest;
import ai.chat2db.spi.model.request.TablesRequest;
import ai.chat2db.spi.sql.Chat2DBContext;
import org.apache.commons.lang3.StringUtils;

final class TrustedMetadataRequestResolver {

    private TrustedMetadataRequestResolver() {
    }

    static TableMetadataRequest table(Long requestDataSourceId, String requestDatabaseName,
            String requestSchemaName, String requestTableName) {
        ConnectInfo connectInfo = Chat2DBContext.getConnectInfo();
        if (connectInfo == null) {
            throw new BusinessException("connection.error");
        }
        if (requestDataSourceId == null || !Objects.equals(requestDataSourceId, connectInfo.getDataSourceId())) {
            throw new BusinessException("common.permissionDenied");
        }

        String trustedDatabaseName = StringUtils.trimToNull(connectInfo.getDatabaseName());
        String trustedSchemaName = StringUtils.trimToNull(connectInfo.getSchemaName());
        requireMatchesTrusted(requestDatabaseName, trustedDatabaseName);
        requireMatchesTrusted(requestSchemaName, trustedSchemaName);

        String requestedTableName = StringUtils.trimToNull(requestTableName);
        if (requestedTableName == null) {
            throw new BusinessException("common.paramError");
        }
        String trustedTableName = resolveTableName(trustedDatabaseName, trustedSchemaName, requestedTableName);
        return new TableMetadataRequest(trustedDatabaseName, trustedSchemaName, trustedTableName);
    }

    private static String resolveTableName(String databaseName, String schemaName, String requestedTableName) {
        IDbMetaData metaData = Chat2DBContext.getDbMetaData();
        Connection connection = Chat2DBContext.getConnection();
        List<String> matchingNames = metaData.tables(connection, new TablesRequest(databaseName, schemaName, null))
                .stream()
                .filter(Objects::nonNull)
                .map(Table::getName)
                .filter(StringUtils::isNotBlank)
                .filter(name -> StringUtils.equalsIgnoreCase(name, requestedTableName))
                .toList();
        return matchingNames.stream()
                .filter(name -> StringUtils.equals(name, requestedTableName))
                .findFirst()
                .orElseGet(() -> matchingNames.size() == 1 ? matchingNames.get(0) : missingTable());
    }

    private static String missingTable() {
        throw new BusinessException("common.paramError");
    }

    private static String stripIdentifierQuote(String identifier) {
        if (identifier == null || identifier.length() < 2) {
            return identifier;
        }
        if ((identifier.startsWith("\"") && identifier.endsWith("\""))
                || (identifier.startsWith("`") && identifier.endsWith("`"))
                || (identifier.startsWith("'") && identifier.endsWith("'"))
                || (identifier.startsWith("[") && identifier.endsWith("]"))) {
            return identifier.substring(1, identifier.length() - 1);
        }
        return identifier;
    }

    private static void requireMatchesTrusted(String requestValue, String trustedValue) {
        String normalizedRequest = stripIdentifierQuote(StringUtils.trimToNull(requestValue));
        if (StringUtils.isBlank(normalizedRequest)) {
            return;
        }
        if (!Objects.equals(normalizedRequest, trustedValue)) {
            throw new BusinessException("common.permissionDenied");
        }
    }
}
