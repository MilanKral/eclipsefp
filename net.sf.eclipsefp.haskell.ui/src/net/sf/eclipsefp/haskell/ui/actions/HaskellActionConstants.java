package net.sf.eclipsefp.haskell.ui.actions;

/**
 * Action constants related to Haskell editor-specific actions.
  *
  * @author B. Scott Michel (scooter.phd@gmail.com
 */
public class HaskellActionConstants {
  /** The action prefix: This is the package name and needs to match the action identifiers in the Haskell UI's
   * plugin.xml. */
  private static final String ActionPrefix = HaskellActionConstants.class.getPackage().getName() + ".";

  /**
   * Comment menu: name of Comment action
   */
  public static final String COMMENT = ActionPrefix.concat( "Comment" );

  /**
   * Comment menu: name of Uncomment action
   */
  public static final String UNCOMMENT = ActionPrefix.concat( "Uncomment" );

  /**
   * Source menu: name of standard Shift Right action
   */
  public static final String SHIFT_RIGHT = ActionPrefix.concat( "ShiftRight" ); //$NON-NLS-1$

  /**
   * Source menu: name of standard Shift Left global action
   */
  public static final String SHIFT_LEFT = ActionPrefix.concat( "ShiftLeft" ); //$NON-NLS-1$
}
