/*
 *    GeoTools - The Open Source Java GIS Toolkit
 *    http://geotools.org
 *
 *    (C) 2002-2015, Open Source Geospatial Foundation (OSGeo)
 *
 *    This library is free software; you can redistribute it and/or
 *    modify it under the terms of the GNU Lesser General Public
 *    License as published by the Free Software Foundation;
 *    version 2.1 of the License.
 *
 *    This library is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *    Lesser General Public License for more details.
 */
package org.geotools.geopkg;

import static java.lang.String.format;
import static org.geotools.geopkg.GeoPackage.*;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.Date;
import java.util.Map;
import java.util.logging.Level;

import javax.sql.DataSource;

import org.geotools.geometry.jts.Geometries;
import org.geotools.geopkg.FeatureEntry;
import org.geotools.geopkg.Entry.DataType;
import org.geotools.geopkg.GeoPackage;
import org.geotools.geopkg.geom.GeoPkgGeomReader;
import org.geotools.geopkg.geom.GeoPkgGeomWriter;
import org.geotools.jdbc.JDBCDataStore;
import org.geotools.jdbc.PreparedStatementSQLDialect;
import org.geotools.referencing.CRS;
import org.opengis.feature.simple.SimpleFeatureType;
import org.opengis.feature.type.GeometryDescriptor;
import org.opengis.feature.type.PropertyDescriptor;
import org.opengis.referencing.FactoryException;
import org.opengis.referencing.crs.CoordinateReferenceSystem;

import com.vividsolutions.jts.geom.Envelope;
import com.vividsolutions.jts.geom.Geometry;
import com.vividsolutions.jts.geom.GeometryFactory;

/**
 * The GeoPackage SQL Dialect.
 * 
 * @author Justin Deoliveira
 * @author Niels Charlier
 *
 */
public class GeoPkgDialect extends PreparedStatementSQLDialect {
   
    protected GeoPkgGeomWriter.Configuration geomWriterConfig;
    
    public GeoPkgDialect(JDBCDataStore dataStore, GeoPkgGeomWriter.Configuration writerConfig) {
        super(dataStore);
        this.geomWriterConfig = writerConfig;
    }

    public GeoPkgDialect(JDBCDataStore dataStore) {
        super(dataStore);
        geomWriterConfig = new GeoPkgGeomWriter.Configuration();
    }

    @Override
    public void initializeConnection(Connection cx) throws SQLException {
        /*DataSource dataSource = dataStore.getDataSource();
        
        new GeoPackage(dataSource).init(cx);*/
        //Made init static as otherwise getDataSource returns null?
        GeoPackage.init(cx);
    }

    @Override
    public boolean includeTable(String schemaName, String tableName, Connection cx) throws SQLException {
        Statement st = cx.createStatement();
        
        try {
            ResultSet rs = st.executeQuery(String.format("SELECT * FROM gpkg_contents WHERE" +
                " table_name = '%s' AND data_type = '%s'", tableName, DataType.Feature.value()));
            try {
                return rs.next();
            }
            finally {
                rs.close();
            }
        }
        finally {
            dataStore.closeSafe(st);
        }
    }

    @Override
    public void encodePrimaryKey(String column, StringBuffer sql) {
        super.encodePrimaryKey(column, sql);
        sql.append(" AUTOINCREMENT");
    }

    @Override
    public void encodeGeometryEnvelope(String tableName, String geometryColumn, StringBuffer sql) {
        encodeColumnName(null, geometryColumn, sql);
    }
     
    @Override
    public Envelope decodeGeometryEnvelope(ResultSet rs, int column, Connection cx)
        throws SQLException, IOException {
        Geometry g = geometry(rs.getBytes(column));
        return g != null ? g.getEnvelopeInternal() : null;
    }

    @Override
    public Geometry decodeGeometryValue(GeometryDescriptor descriptor, ResultSet rs, String column, 
        GeometryFactory factory, Connection cx) throws IOException, SQLException {
        return geometry(rs.getBytes(column));
    }

    @Override
    public void setGeometryValue(Geometry g, int dimension, int srid, Class binding,
            PreparedStatement ps, int column) throws SQLException {
        if (g == null) {
            ps.setNull(1, Types.BLOB);
        }
        else {
            g.setSRID(srid);
            try {
                ps.setBytes(column, new GeoPkgGeomWriter(dimension, geomWriterConfig).write(g));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    Geometry geometry(byte[] b) throws IOException {
        return b != null ? new GeoPkgGeomReader(b).get() : null;
    }

    @Override
    public String getGeometryTypeName(Integer type) {
        return Geometries.getForSQLType(type).getName();
    }

    @Override
    public void registerSqlTypeNameToClassMappings( Map<String, Class<?>> mappings) {
        super.registerSqlTypeNameToClassMappings(mappings);
        mappings.put("DOUBLE", Double.class);
        mappings.put("BOOLEAN", Boolean.class);
    }

    @Override
    public void registerClassToSqlMappings(Map<Class<?>, Integer> mappings) {
        super.registerClassToSqlMappings(mappings);
        // add geometry mappings
        for (Geometries g : Geometries.values()) {
            mappings.put(g.getBinding(), g.getSQLType());
        }
        //override some internal defaults
        mappings.put(Long.class, Types.INTEGER);
        mappings.put(Double.class, Types.REAL);
        mappings.put(Boolean.class, Types.INTEGER);
    }

    @Override
    public void registerSqlTypeToSqlTypeNameOverrides(
            Map<Integer, String> overrides) {
        super.registerSqlTypeToSqlTypeNameOverrides(overrides);

        // The following SQL Data Types are just decorative in SQLite
        // (see https://www.sqlite.org/datatype3.html),
        // but will allow GeoTools to handle some usual java.sql.Types
        // not mapped to raw SQL types by org.sqlite.jdbc3.JDBC3DatabaseMetaData.getTypeInfo()

        // Numbers
        overrides.put(Types.BOOLEAN, "BOOLEAN");
        overrides.put(Types.SMALLINT, "SMALLINT");
        overrides.put(Types.BIGINT, "BIGINT");
        overrides.put(Types.DOUBLE, "DOUBLE");
        overrides.put(Types.NUMERIC, "NUMERIC");

        // Temporal
        overrides.put(Types.DATE, "DATE");
        overrides.put(Types.TIME, "TIME");
        overrides.put(Types.TIMESTAMP, "TIMESTAMP");
    }

    @Override
    public Class<?> getMapping(ResultSet columns, Connection cx) throws SQLException {
        String tbl = columns.getString("TABLE_NAME");
        String col = columns.getString("COLUMN_NAME");

        String sql = format(
            "SELECT b.geometry_type_name" +
             " FROM %s a, %s b" +
            " WHERE a.table_name = b.table_name" +
              " AND b.table_name = ?" +
              " AND b.column_name = ?", GEOPACKAGE_CONTENTS, GEOMETRY_COLUMNS);

        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine(String.format("%s; 1=%s, 2=%s", sql, tbl, col));
        }

        PreparedStatement ps = cx.prepareStatement(sql);
        try {
            ps.setString(1, tbl);
            ps.setString(2, col);

            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                String t = rs.getString(1);
                Geometries g = Geometries.getForName(t);
                if (g != null) {
                    return g.getBinding();
                }
            }

            rs.close();
        }
        finally {
            dataStore.closeSafe(ps);
        }

        return null;
    }

    @Override
    public void postCreateTable(String schemaName, SimpleFeatureType featureType, Connection cx) 
        throws SQLException, IOException {
     
        FeatureEntry fe = (FeatureEntry) featureType.getUserData().get(FeatureEntry.class);
        if (fe == null) {
            fe = new FeatureEntry();
            fe.setIdentifier(featureType.getTypeName());
            fe.setDescription(featureType.getTypeName());
            fe.setTableName(featureType.getTypeName());
            fe.setLastChange(new Date());
        }
        
        GeometryDescriptor gd = featureType.getGeometryDescriptor(); 
        if (gd != null) {
            fe.setGeometryColumn(gd.getLocalName());
            fe.setGeometryType(Geometries.getForBinding((Class) gd.getType().getBinding()));
        }

        CoordinateReferenceSystem crs = featureType.getCoordinateReferenceSystem(); 
        if (crs != null) {
            Integer epsgCode = null;
            try {
                epsgCode = CRS.lookupEpsgCode(crs, true);
            } catch (FactoryException e) {
                LOGGER.log(Level.WARNING, "Error looking up epsg code for " + crs, e);
            }
            if (epsgCode != null) {
                fe.setSrid(epsgCode);
            }
        }

        GeoPackage geopkg = geopkg();
        try {
            geopkg.addGeoPackageContentsEntry(fe);
            geopkg.addGeometryColumnsEntry(fe);

            //other geometry columns are possible
            for (PropertyDescriptor descr : featureType.getDescriptors()) {
                if (descr instanceof GeometryDescriptor) {
                    GeometryDescriptor gd1 = (GeometryDescriptor) descr;
                    if (!(gd1.getLocalName()).equals(fe.getGeometryColumn())) {
                        FeatureEntry fe1 = new FeatureEntry();
                        fe1.init(fe);
                        fe1.setGeometryColumn(gd1.getLocalName());
                        fe1.setGeometryType(Geometries.getForBinding((Class) gd1.getType().getBinding()));
                        geopkg.addGeometryColumnsEntry(fe1);
                    }
                }
            }
        } catch (IOException e) {
            throw new SQLException(e);
        }
    }

    @Override
    public void postDropTable(String schemaName, SimpleFeatureType featureType, Connection cx) throws SQLException {
        super.postDropTable(schemaName, featureType, cx);
        FeatureEntry fe = (FeatureEntry) featureType.getUserData().get(FeatureEntry.class);
        if (fe == null) {
            fe = new FeatureEntry();
            fe.setIdentifier(featureType.getTypeName());
            fe.setDescription(featureType.getTypeName());
            fe.setTableName(featureType.getTypeName());
        }
        GeoPackage geopkg = geopkg();
        try {
            geopkg.deleteGeoPackageContentsEntry(fe);
            geopkg.deleteGeometryColumnsEntry(fe);
        } catch (IOException e) {
            throw new SQLException(e);
        }
    }

    public Integer getGeometrySRID(String schemaName, String tableName, String columnName, Connection cx) throws SQLException {
        try {
            FeatureEntry fe = geopkg().feature(tableName);
            return fe != null ? fe.getSrid() : null;
        } catch (IOException e) {
            throw new SQLException(e);
        }
    }
    
    @Override
    public int getGeometryDimension(String schemaName, String tableName, String columnName, Connection cx) throws SQLException {
        try {
            FeatureEntry fe = geopkg().feature(tableName);
            if (fe != null) {
                return 2 + (fe.isZ() ? 1 : 0) + (fe.isM() ? 1 : 0);
            } else { //fallback - shouldn't happen
                return super.getGeometryDimension(schemaName, tableName, columnName, cx);
            }
        } catch (IOException e) {
            throw new SQLException(e);
        }        
    }

    public CoordinateReferenceSystem createCRS(int srid, Connection cx) throws SQLException {
        try {
            return CRS.decode("EPSG:" + srid);
        }
        catch (Exception e) {
            LOGGER.log(Level.FINE, "Unable to create CRS from epsg code " + srid, e);
            
            //try looking up in spatial ref sys
            String sql = 
                String.format("SELECT definition FROM %s WHERE auth_srid = %d", SPATIAL_REF_SYS, srid);
            LOGGER.fine(sql);

            Statement st = cx.createStatement();
            ResultSet rs = st.executeQuery(sql);
            try {
                if (rs.next()){
                    String wkt = rs.getString(1);
                    try {
                        return CRS.parseWKT(wkt);
                    } catch (Exception e2) {
                        LOGGER.log(Level.FINE, "Unable to create CRS from wkt: " + wkt, e2);
                    }
                }
            }
            finally {
                dataStore.closeSafe(rs);
                dataStore.closeSafe(st);
            }
        }

        return super.createCRS(srid, cx);
    }

    GeoPackage geopkg() {
        return new GeoPackage(dataStore);
    }
}
