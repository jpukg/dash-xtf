/* Generated By:JavaCC: Do not edit this line. FreeformQueryParser.java */
package org.cdlib.xtf.textEngine.freeform;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.util.ArrayList;

import javax.xml.transform.stream.StreamSource;

import net.sf.saxon.om.NodeInfo;
import net.sf.saxon.tinytree.TinyBuilder;
import net.sf.saxon.trans.XPathException;

/** 
 * A grammar-based parser for "freeform queries", constructed with JavaCC.
 * 
 * Designed to parse a query language much like that supported by "gaggle",
 * a little query language used at CDL, which is in turn designed to act
 * much like Google.
 *
 * <p> Uses a tokenizer that should be good for most European-language queries.
 */
@SuppressWarnings("unused")
public class FreeformQueryParser implements FreeformQueryParserConstants {
  /**
   * Simple command-line test driver.
   */
  public static void main(String[] args) throws IOException
  {
    BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
    while (true)
    {
      System.out.print("Enter query: ");
      String text = in.readLine();
      if (text == null || text.length() == 0)
        break;
      FreeformQueryParser parser = new FreeformQueryParser(new StringReader(text));
      try {
        FNode query = parser.Query();
        System.out.println(query.toXML());
      }
      catch (ParseException e) {
        System.out.println("Parse error: " + e);
      }
    }
  }

  /**
   * The result of a parse. A very simple hierarchical structure, basically
   * mirroring the XML that would be generated for an XTF query.
   */
  public class FNode
  {
    public String name;  // Name of the element, such as "query", "and", "term", etc.
    public String text;  // Text of a term element
    public String field; // Field name, or null if specified by parent, or "serverChoice"

    public ArrayList<FNode> children = new ArrayList(); // Sub-elements

    /** Private constructor */
    FNode(String n) { name = n; }

    /** Private constructor */
    FNode(String n, String t) { name = n; text = t; }

    /** Generate XML for this node and its descendants. */
    public String toXML()
    {
      StringBuffer buf = new StringBuffer();
      toXML(0, buf);
      return buf.toString();
    }

    /** Workhorse XML generator */
    private void toXML(int level, StringBuffer buf)
    {
      buf.append(indent(level) + "<" + name);
      if (field != null)
        buf.append(" field=\"" + field + "\"");
      if (text != null && children.isEmpty())
        buf.append(">" + text + "</" + name + ">\n");
      else
      {
        buf.append(">\n");
        level++;
        if (text != null)
          buf.append(indent(level) + text + "\n");
        for (FNode kid : children)
          kid.toXML(level, buf);
        --level;
        buf.append(indent(level) + "</" + name + ">\n");
      }
    }

    /** Convert the query to something more compact than XML */
    public String toString()
    {
      StringBuffer buf = new StringBuffer();
      buf.append(name + "(");
      if (field != null)
        buf.append(field + ": ");
      if (text != null)
        buf.append("\"" + text + "\"");
      boolean first = true;
      for (FNode kid : children) {
        if (!first)
          buf.append(", ");
        first = false;
        buf.append(kid.toString());
      }
      buf.append(")");
      return buf.toString();
    }

    /** Return a string with two spaces per level, used for indenting XML. */
    private String indent(int level)
    {
      StringBuffer buf = new StringBuffer();
      for (int i=0; i<level; i++)
        buf.append("  ");
      return buf.toString();
    }

    /** Add a child to this node */
    private void add(FNode n)
    {
      children.add(n);
    }

    /** If we only have one child, return it. Else, return 'this'. */
    private FNode promoteSingle()
    {
      if (children.size() == 1)
        return children.get(0);
      return this;
    }

    /** Clear the 'field' on this node and all descendants */
    private void clearFields()
    {
      field = null;
      for (FNode kid : children)
        kid.clearFields();
    }

    /**
     * Carry field identifiers to the right. If all fields at one level are
     * the same, move them up to the parent.
     */
    private void resolveFields(int level)
    {
      String f = null;

      // If a field is specified on the parent, ignore specs on children
      if (this.field != null) {
        for (FNode kid : children)
          kid.clearFields();
      }
      else
      {
        // Propagate field names to the right like Google does
        for (FNode kid : children) {
          if (kid.field != null)
            f = kid.field;
          else if (f != null)
            kid.field = f;
        }

        // If any kid has a field specifier, force all of them to.
        if (f != null)
        {
          // If all kids have the same field name, propagate it up.
          boolean anyDiff = false;
          for (FNode kid : children) {
            if (!f.equals(kid.field))
              anyDiff = true;
          }
          if (!anyDiff) {
            for (FNode kid : children)
              kid.field = null;
            this.field = f;
          }

          // Otherwise, assign "serverChoice" to kids that don't have a field.
          else {
            for (FNode kid : children) {
              if (kid.field == null)
                kid.field = "serverChoice";
            }
          }
        }

        // Recursively process descendants
        for (FNode kid : children)
          kid.resolveFields(level+1);
      }

      // If no fields anywhere, assign one at the top level.
      if (level == 0 && this.field == null && f == null)
        field = "serverChoice";
    }
    
    /**
     * In XTF, "not" is always implemented as AND-NOT. So make sure that
     * every not is part of an AND, if necessary sticking an <allDocs>
     * query onto it.
     */
    private void fixNots()
    {
      // Recursively fix nots below here
      for (FNode kid : children)
        kid.fixNots();

      // Now scan for unmatched nots at this level
      for (int i = 0; i < children.size(); i++)
      {
        FNode kid = children.get(i);
        if (!kid.name.equals("not"))
          continue;

        // If the parent isn't an "and", change it.
        if (!name.equals("and") && !name.equals("query"))
          name = "and";

        // Within an AND, we check if there's anything else (positive) 
        // with the same field.
        //
        boolean found = false;
        for (FNode k2 : children) {
          if (k2 == kid || k2.name == "not")
            continue;
          if (k2.field == kid.field)
            found = true;
        }

        // If nothing to match against, add something.
        if (!found) {
          FNode all = new FNode("allDocs");
          FNode and = new FNode("and");
          and.add(all);
          and.add(kid);
          children.set(i, and);
        }
      }
    }
  }

/*****************************************************************************
 * Parser begins here. The grammar builds from the bottom up, beginning with
 * a Term, followed by things that use Term, etc. The root of the grammar
 * is Query, at the very end.
 ****************************************************************************/

/**
 * In general a term is just a single word. But it can also be an email
 * address, symbol, number, etc.
 */
  final public FNode Term() throws ParseException {
    switch ((jj_ntk==-1)?jj_ntk():jj_ntk) {
    case BASIC:
      jj_consume_token(BASIC);
      break;
    case APOSTROPHE:
      jj_consume_token(APOSTROPHE);
      break;
    case ACRONYM:
      jj_consume_token(ACRONYM);
      break;
    case COMPANY:
      jj_consume_token(COMPANY);
      break;
    case EMAIL:
      jj_consume_token(EMAIL);
      break;
    case HOST:
      jj_consume_token(HOST);
      break;
    case NUM:
      jj_consume_token(NUM);
      break;
    case SYMBOL:
      jj_consume_token(SYMBOL);
      break;
    case CJK:
      jj_consume_token(CJK);
      break;
    default:
      jj_la1[0] = jj_gen;
      jj_consume_token(-1);
      throw new ParseException();
    }
    {if (true) return new FNode("term", token.image);}
    throw new Error("Missing return statement in function");
  }

/**
 * A phrase is a quoted string of terms (but we also take care not to barf on
 * reserved words).
 */
  final public FNode Phrase() throws ParseException {
  FNode phrase = new FNode("phrase");
  FNode term;
    jj_consume_token(QUOTE);
    label_1:
    while (true) {
      switch ((jj_ntk==-1)?jj_ntk():jj_ntk) {
      case AND:
      case OR:
      case NOT:
      case PLUS:
      case COLON:
      case OPEN_PAREN:
      case CLOSE_PAREN:
      case BASIC:
      case APOSTROPHE:
      case ACRONYM:
      case COMPANY:
      case EMAIL:
      case HOST:
      case NUM:
      case SYMBOL:
      case CJK:
        ;
        break;
      default:
        jj_la1[1] = jj_gen;
        break label_1;
      }
      switch ((jj_ntk==-1)?jj_ntk():jj_ntk) {
      case BASIC:
      case APOSTROPHE:
      case ACRONYM:
      case COMPANY:
      case EMAIL:
      case HOST:
      case NUM:
      case SYMBOL:
      case CJK:
        term = Term();
                        phrase.add(term);
        break;
      case AND:
      case OR:
      case NOT:
      case PLUS:
      case COLON:
      case OPEN_PAREN:
      case CLOSE_PAREN:
        switch ((jj_ntk==-1)?jj_ntk():jj_ntk) {
        case AND:
          jj_consume_token(AND);
          break;
        case OR:
          jj_consume_token(OR);
          break;
        case NOT:
          jj_consume_token(NOT);
          break;
        case PLUS:
          jj_consume_token(PLUS);
          break;
        case COLON:
          jj_consume_token(COLON);
          break;
        case OPEN_PAREN:
          jj_consume_token(OPEN_PAREN);
          break;
        case CLOSE_PAREN:
          jj_consume_token(CLOSE_PAREN);

          break;
        default:
          jj_la1[2] = jj_gen;
          jj_consume_token(-1);
          throw new ParseException();
        }
        break;
      default:
        jj_la1[3] = jj_gen;
        jj_consume_token(-1);
        throw new ParseException();
      }
    }
    jj_consume_token(QUOTE);
    {if (true) return phrase;}
    throw new Error("Missing return statement in function");
  }

/**
 * You can stick "not" in front of something to negate it. There is post-
 * processing in the Query() production (at the end) to guarantee that each
 * NOT is actually part of an AND-NOT.
 */
  final public FNode Not() throws ParseException {
  FNode node;
  FNode kid;
    jj_consume_token(NOT);
    kid = Component();
    // Handle double-not
    if (kid.name == "not") {
      assert kid.children.size() == 1;
      {if (true) return kid.children.get(0);}
    }
    node = new FNode("not");
    node.add(kid);
    {if (true) return node;}
    throw new Error("Missing return statement in function");
  }

/**
 * We allow parenthesized sub-expressions for grouping
 */
  final public FNode ParenSeq() throws ParseException {
  FNode node;
    jj_consume_token(OPEN_PAREN);
    node = SubQuery();
    jj_consume_token(CLOSE_PAREN);
    {if (true) return node;}
    throw new Error("Missing return statement in function");
  }

/**
 * A component of a query is a phrase, term, parenthesized sequence, or a
 * "not" clause. It can be preceded by an optional field specification.
 */
  final public FNode Component() throws ParseException {
  String field = null;
  FNode node;
    label_2:
    while (true) {
      switch ((jj_ntk==-1)?jj_ntk():jj_ntk) {
      case PLUS:
        ;
        break;
      default:
        jj_la1[4] = jj_gen;
        break label_2;
      }
      jj_consume_token(PLUS);

    }
    label_3:
    while (true) {
      if (jj_2_1(2)) {
        ;
      } else {
        break label_3;
      }
      node = Term();
      jj_consume_token(COLON);
      field = node.text;
    }
    switch ((jj_ntk==-1)?jj_ntk():jj_ntk) {
    case QUOTE:
      node = Phrase();
      break;
    case BASIC:
    case APOSTROPHE:
    case ACRONYM:
    case COMPANY:
    case EMAIL:
    case HOST:
    case NUM:
    case SYMBOL:
    case CJK:
      node = Term();
      break;
    case OPEN_PAREN:
      node = ParenSeq();
      break;
    case NOT:
      node = Not();
      break;
    default:
      jj_la1[5] = jj_gen;
      jj_consume_token(-1);
      throw new ParseException();
    }
    if (field != null)
      node.field = field;
    {if (true) return node;}
    throw new Error("Missing return statement in function");
  }

/**
 * A sequence of components, separated by "OR" or "|"
 */
  final public FNode ORs() throws ParseException {
  FNode node = new FNode("or");
  FNode kid;
    kid = Component();
                        node.add(kid);
    label_4:
    while (true) {
      switch ((jj_ntk==-1)?jj_ntk():jj_ntk) {
      case OR:
        ;
        break;
      default:
        jj_la1[6] = jj_gen;
        break label_4;
      }
      jj_consume_token(OR);
      kid = Component();
                        node.add(kid);
    }
    {if (true) return node.promoteSingle();}
    throw new Error("Missing return statement in function");
  }

/**
 * A sequence of terms (optionally separated by "AND" or "&") is AND-ed together.
 * As in Google, "AND" binds more loosely than "OR", so that A AND B OR C should
 * be grouped like this: A AND (B OR C).
 */
  final public FNode ANDs() throws ParseException {
  FNode node = new FNode("and");
  FNode kid;
    kid = ORs();
                node.add(kid);
    label_5:
    while (true) {
      switch ((jj_ntk==-1)?jj_ntk():jj_ntk) {
      case AND:
      case NOT:
      case PLUS:
      case QUOTE:
      case OPEN_PAREN:
      case BASIC:
      case APOSTROPHE:
      case ACRONYM:
      case COMPANY:
      case EMAIL:
      case HOST:
      case NUM:
      case SYMBOL:
      case CJK:
        ;
        break;
      default:
        jj_la1[7] = jj_gen;
        break label_5;
      }
      label_6:
      while (true) {
        switch ((jj_ntk==-1)?jj_ntk():jj_ntk) {
        case AND:
          ;
          break;
        default:
          jj_la1[8] = jj_gen;
          break label_6;
        }
        jj_consume_token(AND);
      }
      kid = ORs();
                  node.add(kid);
    }
    {if (true) return node.promoteSingle();}
    throw new Error("Missing return statement in function");
  }

/**
 * A single sub-query (can be contained in a paren expr) 
 */
  final public FNode SubQuery() throws ParseException {
  FNode node;
    node = ANDs();
                {if (true) return node;}
    throw new Error("Missing return statement in function");
  }

/**
 * The entire query, which consists of a single sub-query. We apply additional
 * processing to ensure proper structure.
 */
  final public FNode Query() throws ParseException {
  FNode sub;
    sub = SubQuery();
    // Propagate field names from left to right, and from children to parent.
    // Also assign "serverChoice" at the highest level we're forced to.
    //
    sub.resolveFields(0);

    // Create the final wrapper node.
    FNode query = new FNode("query");
    query.add(sub);

    // Guarantee that every NOT is part of a AND-NOT
    query.fixNots();

    // All done!
    {if (true) return query;}
    throw new Error("Missing return statement in function");
  }

  final private boolean jj_2_1(int xla) {
    jj_la = xla; jj_lastpos = jj_scanpos = token;
    try { return !jj_3_1(); }
    catch(LookaheadSuccess ls) { return true; }
    finally { jj_save(0, xla); }
  }

  final private boolean jj_3R_7() {
    Token xsp;
    xsp = jj_scanpos;
    if (jj_scan_token(9)) {
    jj_scanpos = xsp;
    if (jj_scan_token(10)) {
    jj_scanpos = xsp;
    if (jj_scan_token(11)) {
    jj_scanpos = xsp;
    if (jj_scan_token(12)) {
    jj_scanpos = xsp;
    if (jj_scan_token(13)) {
    jj_scanpos = xsp;
    if (jj_scan_token(14)) {
    jj_scanpos = xsp;
    if (jj_scan_token(15)) {
    jj_scanpos = xsp;
    if (jj_scan_token(16)) {
    jj_scanpos = xsp;
    if (jj_scan_token(22)) return true;
    }
    }
    }
    }
    }
    }
    }
    }
    return false;
  }

  final private boolean jj_3_1() {
    if (jj_3R_7()) return true;
    if (jj_scan_token(COLON)) return true;
    return false;
  }

  public FreeformQueryParserTokenManager token_source;
  SimpleCharStream jj_input_stream;
  public Token token, jj_nt;
  private int jj_ntk;
  private Token jj_scanpos, jj_lastpos;
  private int jj_la;
  public boolean lookingAhead = false;
  private boolean jj_semLA;
  private int jj_gen;
  final private int[] jj_la1 = new int[9];
  static private int[] jj_la1_0;
  static {
      jj_la1_0();
   }
   private static void jj_la1_0() {
      jj_la1_0 = new int[] {0x41fe00,0x41ffde,0x1de,0x41ffde,0x10,0x41fea8,0x4,0x41feba,0x2,};
   }
  final private JJCalls[] jj_2_rtns = new JJCalls[1];
  private boolean jj_rescan = false;
  private int jj_gc = 0;

  public FreeformQueryParser(java.io.InputStream stream) {
     this(stream, null);
  }
  public FreeformQueryParser(java.io.InputStream stream, String encoding) {
    try { jj_input_stream = new SimpleCharStream(stream, encoding, 1, 1); } catch(java.io.UnsupportedEncodingException e) { throw new RuntimeException(e); }
    token_source = new FreeformQueryParserTokenManager(jj_input_stream);
    token = new Token();
    jj_ntk = -1;
    jj_gen = 0;
    for (int i = 0; i < 9; i++) jj_la1[i] = -1;
    for (int i = 0; i < jj_2_rtns.length; i++) jj_2_rtns[i] = new JJCalls();
  }

  public void ReInit(java.io.InputStream stream) {
     ReInit(stream, null);
  }
  public void ReInit(java.io.InputStream stream, String encoding) {
    try { jj_input_stream.ReInit(stream, encoding, 1, 1); } catch(java.io.UnsupportedEncodingException e) { throw new RuntimeException(e); }
    token_source.ReInit(jj_input_stream);
    token = new Token();
    jj_ntk = -1;
    jj_gen = 0;
    for (int i = 0; i < 9; i++) jj_la1[i] = -1;
    for (int i = 0; i < jj_2_rtns.length; i++) jj_2_rtns[i] = new JJCalls();
  }

  public FreeformQueryParser(java.io.Reader stream) {
    jj_input_stream = new SimpleCharStream(stream, 1, 1);
    token_source = new FreeformQueryParserTokenManager(jj_input_stream);
    token = new Token();
    jj_ntk = -1;
    jj_gen = 0;
    for (int i = 0; i < 9; i++) jj_la1[i] = -1;
    for (int i = 0; i < jj_2_rtns.length; i++) jj_2_rtns[i] = new JJCalls();
  }

  public void ReInit(java.io.Reader stream) {
    jj_input_stream.ReInit(stream, 1, 1);
    token_source.ReInit(jj_input_stream);
    token = new Token();
    jj_ntk = -1;
    jj_gen = 0;
    for (int i = 0; i < 9; i++) jj_la1[i] = -1;
    for (int i = 0; i < jj_2_rtns.length; i++) jj_2_rtns[i] = new JJCalls();
  }

  public FreeformQueryParser(FreeformQueryParserTokenManager tm) {
    token_source = tm;
    token = new Token();
    jj_ntk = -1;
    jj_gen = 0;
    for (int i = 0; i < 9; i++) jj_la1[i] = -1;
    for (int i = 0; i < jj_2_rtns.length; i++) jj_2_rtns[i] = new JJCalls();
  }

  public void ReInit(FreeformQueryParserTokenManager tm) {
    token_source = tm;
    token = new Token();
    jj_ntk = -1;
    jj_gen = 0;
    for (int i = 0; i < 9; i++) jj_la1[i] = -1;
    for (int i = 0; i < jj_2_rtns.length; i++) jj_2_rtns[i] = new JJCalls();
  }

  final private Token jj_consume_token(int kind) throws ParseException {
    Token oldToken;
    if ((oldToken = token).next != null) token = token.next;
    else token = token.next = token_source.getNextToken();
    jj_ntk = -1;
    if (token.kind == kind) {
      jj_gen++;
      if (++jj_gc > 100) {
        jj_gc = 0;
        for (int i = 0; i < jj_2_rtns.length; i++) {
          JJCalls c = jj_2_rtns[i];
          while (c != null) {
            if (c.gen < jj_gen) c.first = null;
            c = c.next;
          }
        }
      }
      return token;
    }
    token = oldToken;
    jj_kind = kind;
    throw generateParseException();
  }

  static private final class LookaheadSuccess extends java.lang.Error { }
  final private LookaheadSuccess jj_ls = new LookaheadSuccess();
  final private boolean jj_scan_token(int kind) {
    if (jj_scanpos == jj_lastpos) {
      jj_la--;
      if (jj_scanpos.next == null) {
        jj_lastpos = jj_scanpos = jj_scanpos.next = token_source.getNextToken();
      } else {
        jj_lastpos = jj_scanpos = jj_scanpos.next;
      }
    } else {
      jj_scanpos = jj_scanpos.next;
    }
    if (jj_rescan) {
      int i = 0; Token tok = token;
      while (tok != null && tok != jj_scanpos) { i++; tok = tok.next; }
      if (tok != null) jj_add_error_token(kind, i);
    }
    if (jj_scanpos.kind != kind) return true;
    if (jj_la == 0 && jj_scanpos == jj_lastpos) throw jj_ls;
    return false;
  }

  final public Token getNextToken() {
    if (token.next != null) token = token.next;
    else token = token.next = token_source.getNextToken();
    jj_ntk = -1;
    jj_gen++;
    return token;
  }

  final public Token getToken(int index) {
    Token t = lookingAhead ? jj_scanpos : token;
    for (int i = 0; i < index; i++) {
      if (t.next != null) t = t.next;
      else t = t.next = token_source.getNextToken();
    }
    return t;
  }

  final private int jj_ntk() {
    if ((jj_nt=token.next) == null)
      return (jj_ntk = (token.next=token_source.getNextToken()).kind);
    else
      return (jj_ntk = jj_nt.kind);
  }

  private java.util.Vector jj_expentries = new java.util.Vector();
  private int[] jj_expentry;
  private int jj_kind = -1;
  private int[] jj_lasttokens = new int[100];
  private int jj_endpos;

  private void jj_add_error_token(int kind, int pos) {
    if (pos >= 100) return;
    if (pos == jj_endpos + 1) {
      jj_lasttokens[jj_endpos++] = kind;
    } else if (jj_endpos != 0) {
      jj_expentry = new int[jj_endpos];
      for (int i = 0; i < jj_endpos; i++) {
        jj_expentry[i] = jj_lasttokens[i];
      }
      boolean exists = false;
      for (java.util.Enumeration e = jj_expentries.elements(); e.hasMoreElements();) {
        int[] oldentry = (int[])(e.nextElement());
        if (oldentry.length == jj_expentry.length) {
          exists = true;
          for (int i = 0; i < jj_expentry.length; i++) {
            if (oldentry[i] != jj_expentry[i]) {
              exists = false;
              break;
            }
          }
          if (exists) break;
        }
      }
      if (!exists) jj_expentries.addElement(jj_expentry);
      if (pos != 0) jj_lasttokens[(jj_endpos = pos) - 1] = kind;
    }
  }

  public ParseException generateParseException() {
    jj_expentries.removeAllElements();
    boolean[] la1tokens = new boolean[25];
    for (int i = 0; i < 25; i++) {
      la1tokens[i] = false;
    }
    if (jj_kind >= 0) {
      la1tokens[jj_kind] = true;
      jj_kind = -1;
    }
    for (int i = 0; i < 9; i++) {
      if (jj_la1[i] == jj_gen) {
        for (int j = 0; j < 32; j++) {
          if ((jj_la1_0[i] & (1<<j)) != 0) {
            la1tokens[j] = true;
          }
        }
      }
    }
    for (int i = 0; i < 25; i++) {
      if (la1tokens[i]) {
        jj_expentry = new int[1];
        jj_expentry[0] = i;
        jj_expentries.addElement(jj_expentry);
      }
    }
    jj_endpos = 0;
    jj_rescan_token();
    jj_add_error_token(0, 0);
    int[][] exptokseq = new int[jj_expentries.size()][];
    for (int i = 0; i < jj_expentries.size(); i++) {
      exptokseq[i] = (int[])jj_expentries.elementAt(i);
    }
    return new ParseException(token, exptokseq, tokenImage);
  }

  final public void enable_tracing() {
  }

  final public void disable_tracing() {
  }

  final private void jj_rescan_token() {
    jj_rescan = true;
    for (int i = 0; i < 1; i++) {
    try {
      JJCalls p = jj_2_rtns[i];
      do {
        if (p.gen > jj_gen) {
          jj_la = p.arg; jj_lastpos = jj_scanpos = p.first;
          switch (i) {
            case 0: jj_3_1(); break;
          }
        }
        p = p.next;
      } while (p != null);
      } catch(LookaheadSuccess ls) { }
    }
    jj_rescan = false;
  }

  final private void jj_save(int index, int xla) {
    JJCalls p = jj_2_rtns[index];
    while (p.gen > jj_gen) {
      if (p.next == null) { p = p.next = new JJCalls(); break; }
      p = p.next;
    }
    p.gen = jj_gen + xla - jj_la; p.first = token; p.arg = xla;
  }

  static final class JJCalls {
    int gen;
    Token first;
    int arg;
    JJCalls next;
  }

}
