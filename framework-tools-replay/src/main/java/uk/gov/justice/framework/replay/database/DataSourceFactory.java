package uk.gov.justice.framework.replay.database;

import org.apache.commons.dbcp2.BasicDataSource;

public class DataSourceFactory {

    public BasicDataSource create() {
        return new BasicDataSource();
    }
}
