package org.geowebcache.config;

import java.io.IOException;

import org.geowebcache.GeoWebCacheException;
import org.springframework.beans.factory.InitializingBean;

public interface BaseConfiguration extends InitializingBean {

    public static final int BASE_PRIORITY = 0;
    
    /**
     * Reinitialize the configuration from its persistence.
     * 
     * @throws GeoWebCacheException
     */
    void reinitialize() throws GeoWebCacheException;

    /**
     * @return non null identifier for this configuration
     */
    String getIdentifier();
    
    /**
     * The location is a string identifying where this configuration is persisted TileLayerConfiguration
     * implementations may choose whatever form is appropriate to their persistence mechanism and 
     * callers should not assume any particular format. In many but not all cases this will be a URL
     * or filesystem path.
     * 
     * @return Location string for this configuration
     */
    String getLocation();


    /**
     * Saves this configuration
     * 
     * @throws IOException
     * 
     * TODO get rid of this, 
     */
    void save() throws IOException;
    
    /**
     * Get the priority of this configuration when aggregating. Lower values will be used before 
     * higher ones.  This should always return the same value, for a given input.
     */
    default int getPriority(Class<? extends BaseConfiguration> clazz) {
        return BASE_PRIORITY;
    }
    
    @Override
    default void afterPropertiesSet() throws Exception {
        reinitialize();
    }
}