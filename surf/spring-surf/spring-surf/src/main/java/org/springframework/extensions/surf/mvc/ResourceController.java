/*
 * Copyright (C) 2005-2015 Alfresco Software Limited.
 *
 * This file is part of Alfresco
 *
 * Alfresco is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Alfresco is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Alfresco. If not, see <http://www.gnu.org/licenses/>.
 */
package org.springframework.extensions.surf.mvc;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.springframework.extensions.config.WebFrameworkConfigElement;
import org.springframework.extensions.surf.DependencyAggregator;
import org.springframework.extensions.surf.DependencyHandler;
import org.springframework.extensions.surf.DependencyResource;
import org.springframework.extensions.surf.DojoDependencyHandler;

/**
 * <p>Overrides the {@link VirtualizedResourceController} (which in itself overrides the WebScript 
 * {@link org.springframework.extensions.webscripts.servlet.mvc.ResourceController} to use the 
 * {@link DependencyHandler} to process requests for resources that contain content based checksums.</p> 
 * @author David Draper
 */
public class ResourceController extends org.springframework.extensions.webscripts.servlet.mvc.ResourceController
{
    public static final String HTTP_HEADER_EXPIRES = "Expires";
    public static final String HTTP_HEADER_FAR_FUTURE_EXPIRES_VALUE = "Sun, 17-Jan-2038 19:14:07 GMT";
    
    /**
     * <p>A {@link DependencyHandler} is used to lookup resource dependencies including those that are 
     * referenced with a checksum based on their contents. This variable should be set by the Spring
     * application context configuration.</p>
     */
    private DependencyHandler dependencyHandler;

    /**
     * <p>Sets the {@link DependencyHandler} to be used when looking up resources.</p>
     * 
     * @param dependencyHandler DependencyHandler
     */
    public void setDependencyHandler(DependencyHandler dependencyHandler)
    {
        this.dependencyHandler = dependencyHandler;
    }
    
    private DependencyAggregator dependencyAggregator;
    
    public void setDependencyAggregator(DependencyAggregator dependencyAggregator)
    {
        this.dependencyAggregator = dependencyAggregator;
    }
    
    private DojoDependencyHandler dojoDependencyHandler;

    public void setDojoDependencyHandler(DojoDependencyHandler dojoDependencyHandler)
    {
        this.dojoDependencyHandler = dojoDependencyHandler;
    }

    /**
     * <p>The {@link WebFrameworkConfigElement} is used to determine whether or not preview is enabled
     * as this will determine how resources should be looked up.</p>
     */
    private WebFrameworkConfigElement webframeworkConfigElement;

    public void setWebframeworkConfigElement(WebFrameworkConfigElement webframeworkConfigElement)
    {
        this.webframeworkConfigElement = webframeworkConfigElement;
    }

    /**
     * <p>Overrides the default method to use the {@link DependencyHandler} to find resources before
     * falling back on the default WebScript library implementation.</p>
     */
    @Override
    public boolean dispatchResource(String path,
                                    HttpServletRequest request,
                                    HttpServletResponse response) throws ServletException, IOException
    {
        boolean resolved = false;
        
        if (!isAllowedResourcePath(path))
        {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return true;
        }
        
        // ...check the DependencyAggregator...
        DependencyResource resource = this.dependencyAggregator.getCachedDependencyResource(path);
        if (resource != null)
        {
            byte[] bytes = resource.getContent();
            applyHeaders(path, response, bytes.length, 0L);
            ByteArrayInputStream in = new ByteArrayInputStream(bytes);
            copyStream(in, response.getOutputStream());
            resolved = true;
        }
        
        if (!resolved && this.webframeworkConfigElement.isDojoEnabled())
        {
            // ...check the Dojo Dependency handler (if enabled in the Surf configuration)...
            String aggregatedDojoResource = this.dojoDependencyHandler.getCachedResource(path);
            if (aggregatedDojoResource != null)
            {
                byte[] bytes = aggregatedDojoResource.getBytes(this.dependencyHandler.getCharset());
                applyHeaders(path, response, bytes.length, 0L);
                ByteArrayInputStream in = new ByteArrayInputStream(bytes);
                copyStream(in, response.getOutputStream());
                resolved = true;
            }
        }
        
        // ...now check the DependencyHandler...
        if (!resolved)
        {
            InputStream in = this.dependencyHandler.getResourceInputStream(path);
            if (in != null)
            {
                applyHeaders(path, response, in.available(), 0L);
                copyStream(in, response.getOutputStream());
                resolved = true;
            }
        }
        
        // ...Finally drop through again to the Spring ResourceController
        if (!resolved)
        {
            resolved = super.dispatchResource(path, request, response);
        }
        return resolved;
    }
    
    public String getPath(String sourcePath, String dependencyPath)
    {
        String pathPrefix = sourcePath.substring(0, sourcePath.lastIndexOf(FORWARD_SLASH));
        
        // Remove opening and closing quotes...
        if (dependencyPath.startsWith(DOUBLE_QUOTES) || dependencyPath.startsWith(SINGLE_QUOTE))
        {
            dependencyPath = dependencyPath.substring(1);
        }
        if (dependencyPath.endsWith(DOUBLE_QUOTES) || dependencyPath.endsWith(SINGLE_QUOTE))
        {
            dependencyPath = dependencyPath.substring(0, dependencyPath.length() -1);
        }
        
        // Clear any pointless current location markers...
        if (dependencyPath.startsWith(FULL_STOP) && !dependencyPath.startsWith(DOUBLE_FULL_STOP))
        {
            dependencyPath = dependencyPath.substring(1);
        }
        
        StringBuilder sb = new StringBuilder(pathPrefix);
        if (!dependencyPath.startsWith(FORWARD_SLASH))
        {
            sb.append(FORWARD_SLASH);
        }
        sb.append(dependencyPath);
        return sb.toString();
    }
    
    /**
     * Checks whether resource path provided is allowed by web framework configuration. 
     * 
     * @param pathToCheck resource path to check
     * @return <code>true</code> if path is allowed to be viewed, otherwise <code>false</code>
     */
    public boolean isAllowedResourcePath(String pathToCheck)
    {
        if (!pathToCheck.startsWith(FORWARD_SLASH))
        {
            pathToCheck = FORWARD_SLASH + pathToCheck;
        }
        for (Pattern pattern : this.webframeworkConfigElement.getResourcesDeniedPaths())
        {
            Matcher matcher = pattern.matcher(pathToCheck);
            if (matcher.matches())
            {
                // this path is configured as denied
                return false;
            }
        }
        return true;
    }
    
    /**
     * <p>Constant for the forward slash "/"</p>
     */
    public static final String FORWARD_SLASH = "/";
    
    /**
     * <p>Constant for the full stop "." (or period as it is known in the US). In this context it is used to indicate the current location
     * in paths.</p>
     */
    public static final String FULL_STOP = ".";
    
    /**
     * <p>Constant for double full stop ".." (or period as it is known in the US). In this context it is used to indicate the part folder of 
     * the current location.</p>
     */
    public static final String DOUBLE_FULL_STOP = "..";
    
    /**
     * <p>Constant for the double quote '"'.</p>
     */
    public static final String DOUBLE_QUOTES = "\"";
    
    /**
     * <p>Constant for the single quote "'"</p>
     */
    public static final String SINGLE_QUOTE = "'";
}
