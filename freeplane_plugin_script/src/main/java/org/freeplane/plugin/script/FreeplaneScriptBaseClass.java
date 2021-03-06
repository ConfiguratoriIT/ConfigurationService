package org.freeplane.plugin.script;

import java.net.URI;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Date;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.ResourceBundle;
import java.util.regex.Pattern;

import org.codehaus.groovy.runtime.DefaultGroovyMethods;
import org.codehaus.groovy.runtime.InvokerHelper;
import org.freeplane.core.resources.ResourceController;
import org.freeplane.core.ui.components.UITools;
import org.freeplane.core.util.FreeplaneVersion;
import org.freeplane.core.util.HtmlUtils;
import org.freeplane.core.util.LogUtils;
import org.freeplane.core.util.MenuUtils;
import org.freeplane.core.util.TextUtils;
import org.freeplane.features.format.FormatController;
import org.freeplane.features.format.IFormattedObject;
import org.freeplane.features.format.ScannerController;
import org.freeplane.features.link.LinkController;
import org.freeplane.plugin.script.proxy.Convertible;
import org.freeplane.plugin.script.proxy.Proxy;
import org.freeplane.plugin.script.proxy.ScriptUtils;
//import org.sqlite.SQLiteDataSource;

import groovy.lang.Binding;
import groovy.lang.MetaClass;
import groovy.lang.MissingMethodException;
import groovy.lang.MissingPropertyException;
import groovy.lang.Script;

/** All methods of this class are available as "global" methods in every script.
 * Only documented methods are meant to be used in scripts.
 * <p>The following global objects are provided as shortcuts by the binding of this class:
 * <ul>
 * <li><b>ui:</b> see {@link UITools}</li>
 * <li><b>logger:</b> see {@link LogUtils}</li>
 * <li><b>htmlUtils:</b> see {@link HtmlUtils}</li>
 * <li><b>textUtils:</b> see {@link TextUtils}</li>
 * <li><b>menuUtils:</b> see {@link MenuUtils}</li>
 * <li><b>config:</b> see {@link ConfigProperties}</li>
 * </ul>
 * The following classes may also be useful in scripting:
 * <ul>
 * <li>{@link FreeplaneVersion}</li>
 * </ul>
 */
public abstract class FreeplaneScriptBaseClass extends Script {
	/**
	 * Accessor for Freeplane's configuration: In scripts available
	 * as "global variable" <code>config</code>.
	 */
	public static class ConfigProperties {
		public boolean getBooleanProperty(final String name) {
			return ResourceController.getResourceController().getBooleanProperty(name);
		}

		public double getDoubleProperty(final String name, final double defaultValue) {
			return ResourceController.getResourceController().getDoubleProperty(name, defaultValue);
		}

		public int getIntProperty(final String name) {
			return ResourceController.getResourceController().getIntProperty(name);
		}

		public int getIntProperty(final String name, final int defaultValue) {
			return ResourceController.getResourceController().getIntProperty(name, defaultValue);
		}

		public long getLongProperty(final String name, final int defaultValue) {
			return ResourceController.getResourceController().getLongProperty(name, defaultValue);
		}

		public String getProperty(final String name) {
			return ResourceController.getResourceController().getProperty(name);
		}

		public String getProperty(final String name, final String defaultValue) {
			return ResourceController.getResourceController().getProperty(name, defaultValue);
		}

		public Properties getProperties() {
			return ResourceController.getResourceController().getProperties();
		}

		/** support config['key'] from Groovy. */
		public String getAt(final String name) {
            return getProperty(name);
		}
		
		public ResourceBundle getResources() {
		    return ResourceController.getResourceController().getResources();
		}
		
		public String getFreeplaneUserDirectory() {
			return ResourceController.getResourceController().getFreeplaneUserDirectory();
		}
	}

	private final Pattern nodeIdPattern = Pattern.compile("ID_\\d+");
	private final MetaClass nodeMetaClass;
	private Map<Object, Object> boundVariables;
	private Proxy.NodeRO node;
	private Proxy.ControllerRO controller;

	
    public FreeplaneScriptBaseClass() {
	    super();
	    nodeMetaClass = InvokerHelper.getMetaClass(Proxy.NodeRO.class);
	    // Groovy rocks!
	    DefaultGroovyMethods.mixin(Number.class, NodeArithmeticsCategory.class);
	    initBinding();
    }

    @SuppressWarnings("unchecked")
	public void initBinding() {
	    boundVariables = super.getBinding().getVariables();
	    // this is important: we need this reference no matter if "node" is overridden later by the user
	    node = (Proxy.NodeRO) boundVariables.get("node");
	    controller = (Proxy.ControllerRO) boundVariables.get("c");
    }

	@Override
    public void setBinding(Binding binding) {
	    super.setBinding(addStaticBindings(binding));
	    initBinding();
    }

	private Binding addStaticBindings(Binding binding) {
	    for (Entry<String, Object> entry : ScriptingConfiguration.getStaticProperties().entrySet()) {
            binding.setProperty(entry.getKey(), entry.getValue());
        }
	    return binding;
    }

    /* <ul>
	 * <li> translate raw node ids to nodes.
	 * <li> "imports" node's methods into the script's namespace
	 * </ul>
	 */
	public Object getProperty(String property) {
		// shortcuts for the most usual cases
		if (property.equals("node")) {
			return node;
		}			
		if (property.equals("c")) {
			return controller;
		}			
		if (nodeIdPattern.matcher(property).matches()) {
			return N(property);
		}
		else {
			final Object boundValue = boundVariables.get(property);
			if (boundValue != null) {
				return boundValue;
			}
			else {
				try {
					return nodeMetaClass.getProperty(node, property);
				}
				catch (MissingPropertyException e) {
					return super.getProperty(property);
				}
			}
		}
	}

	/*
	 * extends super class version by node instance methods.
	 */
    public Object invokeMethod(String methodName, Object args) {
        try {
            return super.invokeMethod(methodName, args);
        }
        catch (MissingMethodException mme) {
            try {
                return nodeMetaClass.invokeMethod(node, methodName, args);
            }
            catch (MissingMethodException e) {
            	throw e;
            }
        }
    }

	/** Shortcut for node.map.node(id) - necessary for ids to other maps. */
	public Proxy.NodeRO N(String id) {
		final Proxy.NodeRO node = (Proxy.NodeRO) getBinding().getVariable("node");
		return node.getMap().node(id);
	}

	/** Shortcut for node.map.node(id).text. */
	public String T(String id) {
		final Proxy.NodeRO n = N(id);
		return n == null ? null : n.getText();
	}

	/** Shortcut for node.map.node(id).value. */
	public Object V(String id) {
		final Proxy.NodeRO n = N(id);
		try {
	        return n == null ? null : n.getValue();
        }
        catch (ExecuteScriptException e) {
        	return null;
        }
	}

	/** returns valueIfNull if value is null and value otherwise. */
	public Object ifNull(Object value, Object valueIfNull) {
		return value == null ? valueIfNull : value;
	}

	/** rounds a number to integral type. */
    public Long round(final Double d) {
            if (d == null)
                    return null;
            return Math.round(d);
    }
    
    /** round to the given number of decimal places: <code>round(0.1234, 2) &rarr; 0.12</code> */
    public Double round(final Double d, final int precision) {
            if (d == null)
                    return d;
            double factor = 1;
            for (int i = 0; i < precision; i++) {
                    factor *= 10.;
            }
            return Math.round(d * factor) / factor;
    }

    /** parses text to the proper data type, if possible, setting format to the standard. Parsing is configured via
     * config file scanner.xml
     * <pre>
     * assert parse('2012-11-30') instanceof Date
     * assert parse('1.22') instanceof Number
     * // if parsing fails the original string is returned
     * assert parse('2012XX11-30') == '2012XX11-30'
     * 
     * def d = parse('2012-10-30')
     * c.statusInfo = "${d} is ${new Date() - d} days ago"
     * </pre> */
    public Object parse(final String text) {
        return ScannerController.getController().parse(text);
    }

    /** uses formatString to return a FormattedObject.
     * <p><em>Note:</em> If you want to format the node core better use the format node attribute instead:
     * <pre>
     * node.object = new Date()
     * node.format = 'dd/MM/yy'
     * </pre>
     * @return {@link IFormattedObject} if object is formattable and the unchanged object otherwise. */
    public Object format(final Object object, final String formatString) {
        return FormatController.format(object, formatString);
    }

    /** Applies default date-time format for dates or default number format for numbers. All other objects are left unchanged.
     * @return {@link IFormattedObject} if object is formattable and the unchanged object otherwise. */
    public Object format(final Object object) {
        return FormatController.formatUsingDefault(object);
    }

    /** Applies default date format (instead of standard date-time) format on the given date.
     * @return {@link IFormattedObject} if object is formattable and the unchanged object otherwise. */
    public Object formatDate(final Date date) {
        final String format = FormatController.getController().getDefaultDateFormat().toPattern();
        return FormatController.format(date, format);
    }

    /** formats according to the internal standard, that is the conversion will be reversible
     * for types that are handled special by the scripting api namely Dates and Numbers.
     * @see Convertible#toString(Object) */
    public String toString(final Object o) {
        return Convertible.toString(o);
    }

    /** opens a {@link URI} */
    public void loadUri(final URI uri) {
        LinkController.getController().loadURI(uri);
    }

//	/** Shortcut for new {@link org.freeplane.plugin.script.proxy.Convertible}. */
//	public Convertible convertible(String string) {
//		return new Convertible(FormulaUtils.eval string, node.get);
//	}
 // FAGOR
    /*
    private SQLiteDataSource dataSource;
    private Connection connection;
    private String pathDb = "";
    
    public Connection sqliteSetUrl(final String p_path_db) {
    	
    	if (dataSource == null || connection == null || !pathDb.equals(p_path_db)) {
    		dataSource.setUrl("jdbc:sqlite:" + p_path_db);
	    	try {
	    		dataSource = new SQLiteDataSource();
	    		connection = dataSource.getConnection();
	    	} catch(SQLException e) {
	    		System.out.println( e.getClass().getName() + ": " + e.getMessage() );
	            e.printStackTrace();
	    	}
	    	pathDb = p_path_db;
    	}
    	return connection;
    	
    	//connection = ScriptUtils.c().sqliteSetUrl(p_path_db); 
    	//return connection;
    }
    
    public ResultSet executeQuery(final String p_query) {
    	ResultSet executeQuery = null; 
    	try {
    		executeQuery = connection.createStatement().executeQuery(p_query);
    	} catch(SQLException e) {
    		System.out.println( e.getClass().getName() + ": " + e.getMessage() );
            e.printStackTrace();
    	}
    	return executeQuery;
    }
    
    public Connection m_SQL_conn() {
    	return connection;
    }
    
    public String m_SQL_query(String db_file, String p_sql, String p_col) {
    	sqliteSetUrl(db_file);
    	ResultSet rs = executeQuery(p_sql);
    	String pippo = "";
    	try {
    		if (rs.next()) {
    			pippo = rs.getString(p_col);
    		}
    	} catch (SQLException e) {
    		pippo = e.getMessage();
    		System.out.println( e.getClass().getName() + ": " + e.getMessage() );
            e.printStackTrace();
    	}	 
    	return pippo;
    }
    
    public ArrayList<String> m_SQL_query_mult(String db_file, String p_sql, String p_col) {
    	sqliteSetUrl(db_file);
    	ResultSet rs = executeQuery(p_sql);
        ArrayList<String> l_lst = new ArrayList<String>();
    	try {
    		while (rs.next()) {
                System.out.println("\tout: "+rs.getString(p_col));   
                l_lst.add(rs.getString(p_col));
                
    		}
    	} catch (SQLException e) {
    		System.out.println( e.getClass().getName() + ": " + e.getMessage() );
            e.printStackTrace();
    	}	 
    	return l_lst;
    }

    public  ArrayList<String[]> m_SQL_query_mult(String p_path_db, String p_query) {
    	ArrayList l_lst=null;
        try {
            
            long l_t0 = System.currentTimeMillis();
            sqliteSetUrl(p_path_db);
            
            long l_t1 = System.currentTimeMillis();
            ResultSet l_rs = executeQuery(p_query);
            ResultSetMetaData l_rsmd = l_rs.getMetaData(); 
            int l_columnCount = l_rsmd.getColumnCount();
            l_lst = new ArrayList();	

            while (l_rs.next()) {
                    int i = 1;
                    String[] l_strs = new String[l_columnCount];
                    while(i <= l_columnCount) {
                            l_strs[i-1]=l_rs.getString(i++);
                    }			
                    l_lst.add(l_strs);
            }
            
            long l_t2 = System.currentTimeMillis();
            System.out.println("Dt1 (connessione)=" + (l_t1 - l_t0) + ". Dt2 (totale)=" + (l_t2 - l_t0));
            
        } catch (Exception e) {
            System.out.println(e.getClass().getName() + ": " + e.getMessage());
            e.printStackTrace();
            l_lst = null;
        }
        return l_lst;
    }*/
}
