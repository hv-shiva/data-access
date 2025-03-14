/*! ******************************************************************************
 *
 * Pentaho
 *
 * Copyright (C) 2024 by Hitachi Vantara, LLC : http://www.pentaho.com
 *
 * Use of this software is governed by the Business Source License included
 * in the LICENSE.TXT file.
 *
 * Change Date: 2029-07-20
 ******************************************************************************/


package org.pentaho.platform.dataaccess.datasource.wizard.csv;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.LineNumberReader;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.pentaho.di.core.row.ValueMetaInterface;
import org.pentaho.di.core.util.StringEvaluationResult;
import org.pentaho.di.core.util.StringEvaluator;
import org.pentaho.di.trans.steps.textfileinput.TextFileInput;
import org.pentaho.metadata.model.concept.types.DataType;
import org.pentaho.metadata.util.SerializationService;
import org.pentaho.metadata.util.Util;
import org.pentaho.platform.dataaccess.datasource.wizard.models.ColumnInfo;
import org.pentaho.platform.dataaccess.datasource.wizard.models.CsvFileInfo;
import org.pentaho.platform.dataaccess.datasource.wizard.models.CsvParseException;
import org.pentaho.platform.dataaccess.datasource.wizard.models.DataRow;
import org.pentaho.platform.dataaccess.datasource.wizard.models.ModelInfo;
import org.pentaho.platform.dataaccess.datasource.wizard.service.agile.AgileHelper;
import org.pentaho.platform.dataaccess.metadata.messages.Messages;
import org.pentaho.platform.engine.core.system.PentahoBase;
import org.pentaho.platform.engine.core.system.PentahoSystem;
import org.pentaho.platform.util.logging.Logger;
import org.pentaho.reporting.libraries.base.util.CSVTokenizer;

import com.ibm.icu.text.CharsetDetector;
import com.ibm.icu.text.CharsetMatch;
import com.thoughtworks.xstream.XStream;
import com.thoughtworks.xstream.io.xml.DomDriver;

public class CsvUtils extends PentahoBase {

  public static final List<String> NUMBER_FORMATS = Arrays.asList( "#",
    "#,##0.###"
  );

  private static final long serialVersionUID = 2498165533158485182L;

  private Log log = LogFactory.getLog( CsvUtils.class );
  public static final String DEFAULT_RELATIVE_UPLOAD_FILE_PATH =
    File.separatorChar + "system" + File.separatorChar + "metadata" + File.separatorChar + "csvfiles"
      + File.separatorChar; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
  public static final String TMP_FILE_PATH =
    File.separatorChar + "system" + File.separatorChar + File.separatorChar + "tmp" + File.separatorChar;
    //$NON-NLS-1$ //$NON-NLS-2$


  public ModelInfo getFileContents( String project, String name, String delimiter, String enclosure, int rows,
                                    boolean isFirstRowHeader, String encoding ) throws Exception {
    String path;
    if ( name.endsWith( ".tmp" ) ) { //$NON-NLS-1$
      path = PentahoSystem.getApplicationContext().getSolutionPath( TMP_FILE_PATH );
    } else {
      String relativePath = PentahoSystem.getSystemSetting( "file-upload-defaults/relative-path",
              String.valueOf( DEFAULT_RELATIVE_UPLOAD_FILE_PATH ) );  //$NON-NLS-1$
      path = PentahoSystem.getApplicationContext().getSolutionPath( relativePath );
    }

    //PPP-4762 - prevent directory traversal attack
    File filePath = new File(path);
    File fileLocation = new File(path, name);
    if ( !fileLocation.getCanonicalPath().startsWith( filePath.getCanonicalPath() ) ) {
      throw new SecurityException( Messages.getErrorString( "CsvDatasourceServiceImpl.ERROR_0010_DIRECTORY_TRANSVERSAL_ATTACK" ) );
    }

    ModelInfo result = new ModelInfo();
    CsvFileInfo fileInfo = new CsvFileInfo();
    fileInfo.setTmpFilename( name );
    result.setFileInfo( fileInfo );

    fileInfo.setContents( getLinesList( fileLocation.getCanonicalPath(), rows, encoding ) );
    fileInfo.setDelimiter( delimiter );
    fileInfo.setEnclosure( enclosure );
    fileInfo.setHeaderRows( 0 );

    // now try to generate some columns
    return result;
  }

  public ModelInfo generateFields( String project, String filename, int rowLimit, String delimiter, String enclosure,
                                   int headerRows, boolean doData, boolean doColumns, String encoding )
          throws Exception {

    String path;
    if ( filename.endsWith( ".tmp" ) ) { //$NON-NLS-1$
      path = PentahoSystem.getApplicationContext().getSolutionPath( TMP_FILE_PATH );
    } else {
      String relativePath = PentahoSystem.getSystemSetting( "file-upload-defaults/relative-path",
              String.valueOf( DEFAULT_RELATIVE_UPLOAD_FILE_PATH ) );  //$NON-NLS-1$
      path = PentahoSystem.getApplicationContext().getSolutionPath( relativePath );
    }

    String fileLocation = path + filename;
    return generateFields( project, fileLocation, filename, rowLimit, delimiter, enclosure, headerRows, doData, doColumns, encoding

    );
  }

  /* package-local visibility for testing purposes */
  ModelInfo generateFields( String project, String fileLocation, String filename, int rowLimit, String delimiter,
                            String enclosure,
                            int headerRows, boolean doData, boolean doColumns, String encoding )
          throws Exception {
    ModelInfo result = new ModelInfo();
    CsvFileInfo fileInfo = new CsvFileInfo();
    result.setFileInfo( fileInfo );

    CsvInspector inspector = new CsvInspector();
    String sampleLine = getLines( fileLocation, 1, encoding );
    int fileType = inspector.determineFileFormat( sampleLine );

    String contents = getLines( fileLocation, rowLimit, encoding );
    fileInfo.setContents( getLinesList( fileLocation, rowLimit, encoding ) );
    if ( delimiter.equals( "" ) ) { //$NON-NLS-1$
      delimiter = inspector.guessDelimiter( contents );
      enclosure = "\""; //$NON-NLS-1$
      headerRows = 0;
    }
    fileInfo.setDelimiter( delimiter );
    fileInfo.setEnclosure( enclosure );
    fileInfo.setHeaderRows( headerRows );
    fileInfo.setEncoding( encoding ); //Resolves the file encoding using icu4j.
    fileInfo.setProject( project );
    fileInfo.setTmpFilename( filename );

    DataProfile data = getDataProfile( fileInfo, rowLimit, fileLocation, fileType, encoding );
    if ( doData ) {
      result.setData( data.getRows() );
    }
    if ( doColumns ) {
      result.setColumns( data.getColumns() );
    }
    return result;
  }

  private List<String> getColumnData( int columnNumber, String[][] data ) {
    List<String> dataSample = new ArrayList<String>( data.length );
    for ( String[] row : data ) {
      dataSample.add( row[ columnNumber ] );
    }
    return dataSample;
  }

  protected List<String> getLinesList( String fileLocation, int rows, String encoding ) throws IOException {
    List<String> lines = new ArrayList<String>();
    FileInputStream fis = null;
    InputStreamReader isr = null;
    LineNumberReader reader = null;
    try {
      File file = new File( fileLocation );
      fis = new FileInputStream( file );
      isr = new InputStreamReader( fis, encoding );
      reader = new LineNumberReader( isr );
      String line;
      int lineNumber = 0;
      while ( ( line = reader.readLine() ) != null && lineNumber < rows ) {
        lines.add( line );
        lineNumber++;
      }
    } catch ( Exception e ) {
      log.equals( e );
    } finally {
      if ( reader != null ) {
        try {
          reader.close();
        } catch ( Exception e ) {
          log.warn( "Close LineNumberReader exception", e );
        }
      }
      if ( isr != null ) {
        try {
          isr.close();
        } catch ( Exception e ) {
          log.warn( "Close InputStreamReader exception", e );
        }
      }
      if ( fis != null ) {
        try {
          fis.close();
        } catch ( Exception e ) {
          log.warn( "Close FileInputStream exception", e );
        }
      }
    }
    return lines;
  }

  protected String getLines( String fileLocation, int rows, String encoding ) {
    File file = new File( fileLocation );

    // read one line, including all EOL characters
    InputStream in;
    InputStreamReader inr = null;
    StringBuilder line = new StringBuilder();
    int count = 0;
    try {
      in = new FileInputStream( file );
      inr = new InputStreamReader( in, encoding );


      int c = inr.read();
      boolean looking = true;
      while ( looking && c > 0 ) {
        line.append( (char) c );
        if ( c == '\r' || c == '\n' ) {
          // look at the next char
          c = inr.read();
          if ( c == '\r' || c == '\n' ) {
            line.append( (char) c );
            c = inr.read();
          }
          count++;
          if ( count == rows ) {
            looking = false;
          }
        } else {
          c = inr.read();
        }
      }
    } catch ( IOException e ) {
      //do nothing
    } finally {
      if ( inr != null ) {
        try {
          inr.close();
        } catch ( IOException e ) {
          // ignore this one
        }
      }
    }
    return line.toString();

  }

  private DataProfile getDataProfile( CsvFileInfo fileInfo, int rowLimit, String fileLocation, int fileType,
                                      String encoding ) throws Exception {
    DataProfile result = new DataProfile();
    String line = null;
    int row = 0;
    List<List<String>> headerSample = new ArrayList<List<String>>();
    List<List<String>> dataSample = new ArrayList<List<String>>( rowLimit );
    int maxColumns = 0;
    InputStreamReader reader = null;

    try {
      InputStream inputStream = new FileInputStream( fileLocation );
      UnicodeBOMInputStream bomIs = new UnicodeBOMInputStream( inputStream );
      reader = new InputStreamReader( bomIs, encoding );
      bomIs.skipBOM();

      //read each line of text file
      StringBuilder stringBuilder = new StringBuilder( 1000 );
      line = TextFileInput.getLine( null, reader, fileType, stringBuilder );

      while ( line != null && row < rowLimit ) {

        CSVTokenizer csvt = new CSVTokenizer( line, fileInfo.getDelimiter(), fileInfo.getEnclosure() );
        List<String> rowData = new ArrayList<String>();
        int count = 0;

        while ( csvt.hasMoreTokens() ) {
          String token = csvt.nextToken();
          if ( token != null ) {
            token = token.trim();
          }
          rowData.add( token );
          count++;
        }
        if ( maxColumns < count ) {
          maxColumns = count;
        }
        if ( row < fileInfo.getHeaderRows() ) {
          headerSample.add( rowData );
        } else {
          dataSample.add( rowData );
        }
        line = TextFileInput.getLine( null, reader, fileType, stringBuilder );
        row++;
      }

    } catch ( IllegalArgumentException iae ) {
      Logger.error( getClass().getSimpleName(), "There was an issue parsing the CSV file", iae );  //$NON-NLS-1$
      throw new CsvParseException( row + 1, line );
    } catch ( Exception e ) {
      Logger.error( getClass().getSimpleName(), "Could not read CSV", e );  //$NON-NLS-1$
      throw e;
    } finally {

      //close the file
      try {
        if ( reader != null ) {
          reader.close();
        }
      } catch ( Exception e ) {
        throw e;
        // ignore
      }
    }
    String[][] headerValues = new String[ headerSample.size() ][ maxColumns ];
    int rowNo = 0;
    for ( List<String> values : headerSample ) {
      int colNo = 0;
      for ( String value : values ) {
        headerValues[ rowNo ][ colNo ] = value;
        colNo++;
      }
      rowNo++;
    }

    int[] fieldLengths = new int[ maxColumns ];

    String[][] dataValues = new String[ dataSample.size() ][ maxColumns ];
    DataRow[] data = new DataRow[ dataSample.size() ];
    rowNo = 0;
    for ( List<String> values : dataSample ) {
      int colNo = 0;
      for ( String value : values ) {
        dataValues[ rowNo ][ colNo ] = value;

        int currentMaxLength = fieldLengths[ colNo ];
        if ( value.length() > currentMaxLength ) {
          fieldLengths[ colNo ] = value.length();
        }
        colNo++;
      }
      data[ rowNo ] = new DataRow();
      data[ rowNo ].setCells( dataValues[ rowNo ] );
      rowNo++;
    }

    result.setRows( data );

    DecimalFormat df = new DecimalFormat( "000" ); //$NON-NLS-1$
    ColumnInfo[] profiles = new ColumnInfo[ maxColumns ];
    for ( int idx = 0; idx < maxColumns; idx++ ) {
      ColumnInfo profile = new ColumnInfo();
      profiles[ idx ] = profile;
      String title = CsvFileInfo.DEFAULT_COLUMN_NAME_PREFIX + df.format( idx + 1 );
      String colId = "PC_" + idx; //$NON-NLS-1$

      if ( headerValues.length > 0 ) {
        if ( headerValues[ headerValues.length - 1 ][ idx ] != null ) {
          title = headerValues[ headerValues.length - 1 ][ idx ];
          colId = title;
          if ( !Util.validateId( title ) ) {
            colId = Util.toId( colId );
          }
        }
      }
      profile.setTitle( title );
      profile.setId( colId );


      List<String> samples = getColumnData( idx, dataValues );

      assumeColumnDetails( profile, samples );

    }
    result.setColumns( profiles );
    return result;
  }

  protected void assumeColumnDetails( ColumnInfo profile, List<String> samples ) {
    StringEvaluator eval = new StringEvaluator( false, NUMBER_FORMATS, ColumnInfo.DATE_FORMATS );
    assumeColumnDetails( profile, samples, eval );
  }

  protected void assumeColumnDetails( ColumnInfo profile, List<String> samples, StringEvaluator stringEvaluator ) {
    for ( String sample : samples ) {
      stringEvaluator.evaluateString( sample );
    }
    StringEvaluationResult result = stringEvaluator.getAdvicedResult();
    ValueMetaInterface meta = result.getConversionMeta();

    assumeColumnDetails( profile, meta );
  }

  protected void assumeColumnDetails( ColumnInfo profile, ValueMetaInterface meta ) {
    profile.setFormat( meta.getConversionMask() );
    profile.setPrecision( convertPrecision( meta ) );
    profile.setDataType( convertDataType( meta ) );
    profile.setLength( convertLength( meta ) );
  }

  @Override
  public Log getLogger() {
    return log;
  }


  public String getEncoding( String fileName ) throws Exception {

    String path;
    if ( fileName.endsWith( ".tmp" ) ) { //$NON-NLS-1$
      path = PentahoSystem.getApplicationContext().getSolutionPath( TMP_FILE_PATH );
    } else {
      String relativePath = PentahoSystem.getSystemSetting( "file-upload-defaults/relative-path",
              String.valueOf( DEFAULT_RELATIVE_UPLOAD_FILE_PATH ) );  //$NON-NLS-1$
      path = PentahoSystem.getApplicationContext().getSolutionPath( relativePath );
    }
    String fileLocation = path + fileName;

    String encoding;
    try {
      byte[] bytes = new byte[ 1024 ];
      InputStream inputStream = new FileInputStream( new File( fileLocation ) );
      inputStream.read( bytes );
      CharsetDetector charsetDetector = new CharsetDetector();
      charsetDetector.setText( bytes );
      CharsetMatch charsetMatch = charsetDetector.detect();
      encoding = charsetMatch.getName();
      inputStream.close();
    } catch ( Exception e ) {
      log.error( e );
      throw e;
    }
    return encoding;
  }

  public ModelInfo getModelInfo( String project, String filename ) throws FileNotFoundException {
    XStream xstream =
      SerializationService.createXStreamWithAllowedTypes( new DomDriver( "UTF-8" ), ModelInfo.class, ColumnInfo.class,
        CsvFileInfo.class, CsvParseException.class,
        DataRow.class );
    xstream.alias( "modelInfo", ModelInfo.class ); //$NON-NLS-1$
    xstream.alias( "columnInfo", ColumnInfo.class ); //$NON-NLS-1$
    String filepath = AgileHelper.getFolderPath( project ) + "/" + filename + ".xml"; //$NON-NLS-1$ //$NON-NLS-2$
    System.out.println( filepath );
    File f = new File( filepath );
    FileInputStream fis = new FileInputStream( f );
    return (ModelInfo) xstream.fromXML( fis );
  }

  protected int convertPrecision(  ValueMetaInterface meta  ) {
    return meta.getPrecision() > 0 ? meta.getPrecision() : 0;
  }

  protected DataType convertDataType(  ValueMetaInterface meta  ) {
    return convertDataType( meta.getType() );
  }

  protected DataType convertDataType( int type ) {
    switch ( type ) {
      case ValueMetaInterface.TYPE_NUMBER:
      case ValueMetaInterface.TYPE_INTEGER:
      case ValueMetaInterface.TYPE_BIGNUMBER:
        return DataType.NUMERIC;
      case ValueMetaInterface.TYPE_DATE:
        return DataType.DATE;
      case ValueMetaInterface.TYPE_BOOLEAN:
        return DataType.BOOLEAN;
      default:
        return DataType.STRING;
    }
  }

  protected int convertLength( ValueMetaInterface meta ) {
    int size;
    if ( meta.isString() ) {
      // pad the string lengths
      size = meta.getLength() + ( meta.getLength() / 2 );
    } else if ( meta.isInteger() ) {
      size = meta.getLength();
    } else {
      size = meta.getPrecision() > 0 ? meta.getLength() : 0;
    }
    return size;
  }

  private static class DataProfile {
    DataRow[] rows = null;
    ColumnInfo[] columns = null;

    public DataRow[] getRows() {
      return rows;
    }

    public void setRows( DataRow[] rows ) {
      this.rows = rows;
    }

    public ColumnInfo[] getColumns() {
      return columns;
    }

    public void setColumns( ColumnInfo[] columns ) {
      this.columns = columns;
    }
  }

}
