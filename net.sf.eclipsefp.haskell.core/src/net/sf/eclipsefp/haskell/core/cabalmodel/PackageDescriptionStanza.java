// Copyright (c) 2006 by Leif Frenzel <himself@leiffrenzel.de>
// All rights reserved.
package net.sf.eclipsefp.haskell.core.cabalmodel;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.io.Writer;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.sf.eclipsefp.haskell.util.FileUtil;
import net.sf.eclipsefp.haskell.util.PlatformUtil;


/** <p>represents a stanza in a package description.</p>
  *
  * @author Leif Frenzel
  */
public class PackageDescriptionStanza {
  private static class CabalSyntaxMap<V> extends LinkedHashMap<String, V>{
    /**
    *
    */
   private static final long serialVersionUID = -630958988149146289L;

   @Override
   public V get(final Object key) {
     if (key instanceof String){
       return super.get(((String)key).toLowerCase());
     } else if (key instanceof CabalSyntax){
       return super.get(((CabalSyntax)key).getCabalName().toLowerCase());
     }
     return null;
   }

 }

  private final String name;
  private int startLine;
  private int endLine;
  private int indent;
  /**
   * field names to values
   */
  private final Map<String, String> properties=new CabalSyntaxMap<>();
  /**
   * field names to real names (case may be different)
   */
  private final Map<String, String> realNames=new CabalSyntaxMap<>();
  /**
   * field names to positions
   */
  private final Map<String, ValuePosition> positions=new CabalSyntaxMap<>();
  private final CabalSyntax type;

  private final List<PackageDescriptionStanza> stanzas=new LinkedList<>();

  /**
   * this field is kind of a hack, this is used to append properly to the end of a Cabal file
   * if it doesn't end with a NL
   */
  boolean needNL=false;

  private final PackageDescription pd;

  private String realTypeName;

  PackageDescriptionStanza(final PackageDescription pd,final CabalSyntax type,final String name,
      final int startLine){
    this.type=type;
    this.name = name;
    this.startLine = startLine;
    this.pd=pd;
  }

  public PackageDescription getPackageDescription(){
    return this.pd;
  }

  /**
   * clone and add the stanza to the given description
   * @param desc
   * @return
   */
  public PackageDescriptionStanza move(final PackageDescription desc){
    int startLine=desc.getStanzas().get(desc.getStanzas().size()-1).getEndLine()+1;
    int diff=startLine-getStartLine();
    PackageDescriptionStanza npds= move(diff,this,desc);
    desc.getStanzas().add( npds );
    return npds;
  }

  private static PackageDescriptionStanza move(final int diff,final PackageDescriptionStanza me,final PackageDescription desc){
    PackageDescriptionStanza pds=new PackageDescriptionStanza(desc,me.getType(),me.getName(),me.getStartLine()+diff);
    pds.endLine=me.endLine+diff;
    pds.indent=me.indent;
    pds.realTypeName=me.realTypeName;
    pds.needNL=me.needNL;
    pds.properties.putAll( me.properties );
    pds.realNames.putAll( me.realNames );

    for (String entry : me.positions.keySet()) {
      ValuePosition vp = me.positions.get( entry ).clone();
      vp.diffLine( diff );
      pds.positions.put( entry, vp);
    }

    for (PackageDescriptionStanza cpds:me.stanzas){
      PackageDescriptionStanza ncpds=move(diff,cpds,desc);
      pds.stanzas.add(ncpds);
    }
    return pds;
  }

  public void diffLine ( final int diff ) {
    this.startLine += diff;
    this.endLine += diff;
    for (String entry : positions.keySet()) {
      ValuePosition vp = positions.get( entry );
      vp.diffLine( diff );
    }
    for (PackageDescriptionStanza pds:stanzas){
      pds.diffLine( diff );
    }
  }

  public void diffLineIf ( final int diff, final int linePos ) {
    boolean needPos=false;
    if (this.startLine>=linePos){
      this.startLine += diff;
      needPos=true;
    }
    if (this.endLine>=linePos){
      this.endLine += diff;
      needPos=true;
    }
    if ( needPos){
      for (String entry : positions.keySet()) {
        ValuePosition vp = positions.get( entry );
        if (vp.getStartLine()>=linePos){
          vp.diffLine( diff );
        }
      }
    }
    for (PackageDescriptionStanza st:stanzas){
      st.diffLineIf( diff, linePos );
    }
  }

  public void setStartLine ( final int startLine ) {
    this.startLine = startLine;
  }


  public void setEndLine( final int endLine ) {
    this.endLine = endLine;
  }

  public List<PackageDescriptionStanza> getStanzas() {
    return stanzas;
  }

  public CabalSyntax getType() {
    return type;
  }


  public int getIndent() {
    return indent;
  }


  public void setIndent( final int indent ) {
    this.indent = indent;
  }

  public Map<String, String> getProperties() {
    return properties;
  }


  public Map<String, ValuePosition> getPositions() {
    return positions;
  }


  /*public ValuePosition update(final CabalSyntax field,final String value){
    return update(field.getCabalName(),value);
  }

  public ValuePosition update(final String field,final String value){
    getProperties().put(field.toLowerCase(),value);
    ValuePosition vp=getPositions().get( field.toLowerCase() );
    if (vp==null){
      return new ValuePosition(endLine,endLine,0);
    }
    return vp;
  }*/

  public RealValuePosition update(final CabalSyntax field,final String value){
    String key=field.getCabalName().toLowerCase( Locale.ENGLISH );
    String realValue = value;
    if (value!=null && value.trim().length()==0){
      realValue=null;
    }
    Map.Entry<String,String> eLast=null;
    if (realValue!=null){
      if (needNL){
        for (Map.Entry<String,String> e:getProperties().entrySet()){
          eLast=e;
        }
      }
      getProperties().put(key,realValue);
    } else {
      getProperties().remove(key);
    }
    ValuePosition oldVP=getPositions().get( field );
    int indent=0;
    int subIndent=0;
    int insertSub=0;
    StringBuilder sb=new StringBuilder();
    if (oldVP==null){
      oldVP=new ValuePosition(getEndLine(),getEndLine(),0);
      Collection<ValuePosition> vps=getPositions().values();
      for (int a=0;a<getIndent();a++){
        sb.append( ' ');
      }
      String name=getRealNames().get( key );
      sb.append(name!=null?name: field.getCabalName() );
      sb.append( ":" ); //$NON-NLS-1$
      int spaces=4;
      if (vps.isEmpty()){
        subIndent=getIndent()+key.length()+5;
      } else {
        ValuePosition vp=vps.iterator().next();
        indent=subIndent=vp.getSubsequentIndent();
        spaces=indent-(key.length() +1+getIndent());
        if (needNL && eLast!=null){
          addLeadingNL(sb,oldVP,eLast);
          //insertSub+=PlatformUtil.NL.length();
        }
      }
      spaces=Math.max( spaces, 1 );
      for (int a=0;a<spaces;a++){
        sb.append( ' ');
      }
      insertSub=sb.length();

    } else {
      subIndent=oldVP.getSubsequentIndent();
      indent=oldVP.getInitialIndent();
      insertSub=0;
    }
    if (realValue==null){
      getPositions().remove( key );
      // remove field name too
      oldVP.setInitialIndent( 0 );
      for (PackageDescriptionStanza st:getPackageDescription().getStanzas()){
        st.diffLineIf( oldVP.getStartLine()-oldVP.getEndLine(), oldVP.getEndLine() );
      }
      //oldVP.setInitialIndent( 0 );
      return new RealValuePosition( oldVP,""); //$NON-NLS-1$
    }

    BufferedReader br=new BufferedReader( new StringReader( realValue ) );
    try {

      String line=br.readLine();
      int count=0;
      while (line!=null){
        for (int a=0;count>0 && a<subIndent;a++){
          sb.append( ' ');
        }
        count++;
        if (line.trim().length()==0){
          line= "."+line ; //$NON-NLS-1$
        }
        sb.append( line );
        sb.append( PlatformUtil.NL );
        line=br.readLine();
      }
      if (count>1){ // when several line, start at next one
        for (int a=0;a<subIndent;a++){
          sb.insert( insertSub, ' ');
        }
        sb.insert( insertSub, PlatformUtil.NL );
        count++;
      }
      ValuePosition newVP=new ValuePosition(oldVP.getStartLine(),oldVP.getStartLine()+count,indent);
      newVP.setSubsequentIndent( subIndent );
      getPositions().put( key, newVP );
      int diff=newVP.getEndLine()-oldVP.getEndLine();
      if (oldVP.getEndLine()==getEndLine()){
        needNL=false;
      } else {
        for (PackageDescriptionStanza st:getPackageDescription().getStanzas()){
          st.diffLineIf( diff, oldVP.getEndLine() );
        }
      }
      setEndLine( getEndLine()+diff );
      return new RealValuePosition( oldVP,sb.toString());
    } catch (IOException ioe){
      return null;
    }
  }

  /**
   * if the cabal file didn't end with a NL, we need to add it when we modify the document
   * @param sb the builder for the final value
   * @param oldVP the current position we're inserting
   * @param eLast the entry for the last property in the stanza
   */
  private void addLeadingNL(final StringBuilder sb,final ValuePosition oldVP,final Map.Entry<String,String> eLast){
    needNL=false;
    sb.insert( 0, PlatformUtil.NL );
    oldVP.setStartLine(oldVP.getStartLine()-1);
    //oldVP.setEndLine(oldVP.getEndLine()-1);
    for (CabalSyntax cs:CabalSyntax.values()){
      if (cs.getCabalName().toLowerCase().equals( eLast.getKey() )){
        RealValuePosition rvpLast=update(cs,eLast.getValue());
        if (rvpLast.getStartLine()+1==rvpLast.getEndLine()){
          oldVP.setInitialIndent(rvpLast.getInitialIndent()+rvpLast.getRealValue().length()-PlatformUtil.NL.length());
        } else {
          BufferedReader br=new BufferedReader( new StringReader( rvpLast.getRealValue() ) );
          try {
            String line=br.readLine();
            while (line!=null){
              String newLine=br.readLine();
              if (newLine==null){
                oldVP.setInitialIndent(line.length());
              }
              line=newLine;
            }

          } catch (IOException ioe){
            // cannot happen
          }
        }
        break;
      }
    }
  }

  public RealValuePosition addToPropertyList(final CabalSyntax field,final String value){
   String s=getProperties().get( field );
   List<String> ls=PackageDescriptionLoader.parseList( s );
   // short lists we hope
   if (!ls.contains( value )){
     StringBuilder newValue=new StringBuilder();
     if (s!=null){
       newValue.append( s );
       if (s.trim().length()>0){
         if (!s.trim().endsWith( "," )){ //$NON-NLS-1$
           newValue.append(","); //$NON-NLS-1$
         }
         if (!s.endsWith( PlatformUtil.NL )){
           newValue.append(PlatformUtil.NL);
         }
         /*if (!s.endsWith( " " )){ //$NON-NLS-1$
           newValue.append(" "); //$NON-NLS-1$
         }*/
       }
     }
     newValue.append(value);
     return update(field, newValue.toString() );
   }
   return null;
  }

  public RealValuePosition removeFromPropertyList(final CabalSyntax field,final String value){
    String s=getProperties().get( field );
    if (s!=null){
      List<String> ls=PackageDescriptionLoader.parseList( s );

      StringBuilder newValue=new StringBuilder(s.length());
      boolean changed=false;
      for (String token:ls){
        if (!value.equals( token )){
          if(newValue.length()>0){
            newValue.append( ","+PlatformUtil.NL ); //$NON-NLS-1$
          }
          newValue.append( token);
        } else {
          changed=true;
        }
      }
      if (changed){
        return update(field, newValue.toString() );
      }
    }
    return null;

   }

  public RealValuePosition removePrefixFromPropertyList(final CabalSyntax field,final String prefix,final String seps){
    String s=getProperties().get( field );
    if (s!=null){
      List<String> ls=PackageDescriptionLoader.parseList( s,seps );

      StringBuilder newValue=new StringBuilder(s.length());
      boolean changed=false;
      String prefixSp=prefix+" "; //$NON-NLS-1$
      for (String token:ls){
        if ((!prefix.equals( token.trim() )) && (!token.trim().startsWith( prefixSp ))){
          if(newValue.length()>0){
            newValue.append( ", " ); //$NON-NLS-1$
          } else if (token.startsWith( PlatformUtil.NL )){
            token=token.substring( PlatformUtil.NL.length() );
          }
          newValue.append( token);
        } else {
          changed=true;
        }
      }
      if (changed){
        return update(field, newValue.toString() );
      }
    }
    return update(field, s);
   }

  public String getName() {
    return name;
  }

  public int getStartLine() {
    return startLine;
  }

  public int getEndLine() {
    return endLine;
  }

  public String toTypeName(){
 // this is equivalent to Component toString()
    String tn=realTypeName!=null?realTypeName:String.valueOf( getType() ) ;
    return tn + (getName()!=null?" "+getName():"");  //$NON-NLS-1$//$NON-NLS-2$
  }

  // interface methods of Object
  //////////////////////////////

  @Override
  public String toString() {
    return   (getName()!=null?getName():String.valueOf( getType() ))
           + " (line " //$NON-NLS-1$
           + startLine
           + "-" //$NON-NLS-1$
           + endLine
           + ")"; //$NON-NLS-1$
  }

  public Collection<String> getDependentPackages(){
    Collection<String> ret=new HashSet<>();
    String val=getProperties().get( CabalSyntax.FIELD_BUILD_DEPENDS);
    if (val!=null && val.length()>0){
      List<String> ls=PackageDescriptionLoader.parseList( val,"," ); //$NON-NLS-1$
      for (String s:ls){
        int a=0;
        for (;a<s.length();a++){
          char c=s.charAt( a );
          if (c == '=' || c== '>' || c== '<'){
            break;
          }
        }
        s=s.substring(0,a).trim();
        ret.add( s);
      }
    }
    for (PackageDescriptionStanza st:getStanzas()){
      ret.addAll(st.getDependentPackages());
    }
    return ret;
  }

  public Collection<String> getSourceDirs(){
    Collection<String> ret=new HashSet<>();
    String val=getProperties().get( CabalSyntax.FIELD_HS_SOURCE_DIRS);
    if (val!=null && val.length()>0){
      ret.addAll(PackageDescriptionLoader.parseList( val));
    }
    // backward compatibility
    val=getProperties().get( "hs-source-dir"); //$NON-NLS-1$
    if (val!=null && val.length()>0){
      ret.addAll(PackageDescriptionLoader.parseList( val));
    }
    for (PackageDescriptionStanza st:getStanzas()){
      ret.addAll(st.getSourceDirs());
    }
    if (ret.isEmpty() && (getType()!=null && isSourceStanza())){
      ret.add("."); //$NON-NLS-1$
    }
    return ret;
   }

  /**
   * Are we a stanza that contains sources?
   * @return
   */
  public boolean isSourceStanza(){
    return getType()!=null &&
        (   getType().equals( CabalSyntax.SECTION_EXECUTABLE)
        || getType().equals( CabalSyntax.SECTION_LIBRARY)
        || getType().equals( CabalSyntax.SECTION_TESTSUITE)
        || getType().equals( CabalSyntax.SECTION_BENCHMARK));
  }

  /**
   * are we a stanza that can contain a Main module?
   * @return
   */
  public boolean isMainStanza(){
    return getType()!=null &&
        (   getType().equals( CabalSyntax.SECTION_EXECUTABLE)
        || getType().equals( CabalSyntax.SECTION_TESTSUITE)
        || getType().equals( CabalSyntax.SECTION_BENCHMARK));
  }

  public Collection<String> getNonHaskellFiles(){
    Collection<String> ret=new HashSet<>();
    String val=getProperties().get( CabalSyntax.FIELD_INCLUDES);
    if (val!=null && val.length()>0){
      ret.addAll(PackageDescriptionLoader.parseList( val));
    }
    val=getProperties().get( CabalSyntax.FIELD_C_SOURCES);
    if (val!=null && val.length()>0){
      ret.addAll(PackageDescriptionLoader.parseList( val));
    }
    for (PackageDescriptionStanza st:getStanzas()){
      ret.addAll(st.getNonHaskellFiles());
    }
    return ret;
  }

  public void dump(final Writer w,final int indent) throws IOException {
    int indent2=indent;
    if (getType()!=null){
      for (int a=0;a<indent;a++){
        w.write(" "); //$NON-NLS-1$
      }
      w.write( toTypeName() );
      w.write(PlatformUtil.NL);
      indent2=indent+2;
    }
    int max=0;
    for (String p:positions.keySet()){
      max=Math.max( p.length(), max );
    }

    for (String p:positions.keySet()){
      for (int a=0;a<indent2;a++){
        w.write(" "); //$NON-NLS-1$
      }
      String name=getRealNames().get( p );
      w.write( name!=null?name:p );
      ValuePosition vp=positions.get(p);
      w.write( ":" ); //$NON-NLS-1$
      for (int a=0;a<max+2-p.length();a++){
        w.write(" "); //$NON-NLS-1$
      }
      boolean first=true;
      String value=properties.get(p);
      BufferedReader br=new BufferedReader( new StringReader( value ) );
      try {
        String line=br.readLine();
        while (line!=null){
          String line2=br.readLine();
          if (!first){
            for (int a=0;a<vp.getSubsequentIndent();a++){
              w.write(" "); //$NON-NLS-1$
            }
          } else {
            first=false;
            // multiple lines: start on next line
            if (line2!=null){
              w.write(PlatformUtil.NL);
              for (int a=0;a<vp.getSubsequentIndent();a++){
                w.write(" "); //$NON-NLS-1$
              }
            }
          }
          if (line.trim().length()==0){
            line= "."+line ; //$NON-NLS-1$
          }
            w.write(line);
            w.write(PlatformUtil.NL);
            line=line2;
          }

      } catch (IOException ignore){
        // cannot happen
      }
    }
    w.write(PlatformUtil.NL);
    for (PackageDescriptionStanza pds:stanzas){
      pds.dump( w, indent2 );
    }

  }

  public ModuleInclusionType getModuleInclusionType(final String module){
    String s=getProperties().get( CabalSyntax.FIELD_OTHER_MODULES );
    List<String> ls=PackageDescriptionLoader.parseList( s );
    if (ls.contains( module )){
      return ModuleInclusionType.INCLUDED;
    }
    s=getProperties().get( CabalSyntax.FIELD_EXPOSED_MODULES );
    ls=PackageDescriptionLoader.parseList( s );
    if (ls.contains( module )){
      return ModuleInclusionType.EXPOSED;
    }
    s=getProperties().get( CabalSyntax.FIELD_MAIN_IS );
    String f=module.replace( '.', '/' );
    if ((f+"."+FileUtil.EXTENSION_HS).equals(s) || (f+"."+FileUtil.EXTENSION_LHS).equals(s)){ //$NON-NLS-1$ //$NON-NLS-2$
      return ModuleInclusionType.MAIN;
    }
    for (PackageDescriptionStanza pds:getStanzas()){
      ModuleInclusionType inSubSection=pds.getModuleInclusionType( module );
      if (!inSubSection.equals( ModuleInclusionType.MISSING )){
        return inSubSection;
      }
    }
    return ModuleInclusionType.MISSING;
  }

  public Set<String> listAllModules(){
    Set<String> ret=new HashSet<>();
    String s=getProperties().get( CabalSyntax.FIELD_OTHER_MODULES );
    ret.addAll(PackageDescriptionLoader.parseList( s ));
    s=getProperties().get( CabalSyntax.FIELD_EXPOSED_MODULES );
    ret.addAll(PackageDescriptionLoader.parseList( s ));
    s=getProperties().get( CabalSyntax.FIELD_MAIN_IS );
    if (s!=null){
      ret.add("Main"); //$NON-NLS-1$
    }
    for (PackageDescriptionStanza pds:getStanzas()){
      ret.addAll( pds.listAllModules() );
    }
    return ret;
  }

  public Set<String> listExposedModules(){
    Set<String> ret=new HashSet<>();
    String s=getProperties().get( CabalSyntax.FIELD_EXPOSED_MODULES );
    ret.addAll(PackageDescriptionLoader.parseList( s ));
    for (PackageDescriptionStanza pds:getStanzas()){
      ret.addAll( pds.listExposedModules() );
    }
    return ret;
  }

  public Map<String, String> getRealNames() {
    return realNames;
  }


  public String getRealTypeName() {
    return realTypeName;
  }


  public void setRealTypeName( final String realTypeName ) {
    this.realTypeName = realTypeName;
  }
}
