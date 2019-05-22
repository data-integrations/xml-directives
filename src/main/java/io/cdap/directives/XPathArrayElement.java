/*
 *  Copyright © 2017 Cask Data, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not
 *  use this file except in compliance with the License. You may obtain a copy of
 *  the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations under
 *  the License.
 */

package io.cdap.directives;

import io.cdap.cdap.api.annotation.Description;
import io.cdap.cdap.api.annotation.Name;
import io.cdap.cdap.api.annotation.Plugin;
import io.cdap.wrangler.api.Arguments;
import io.cdap.wrangler.api.Directive;
import io.cdap.wrangler.api.DirectiveExecutionException;
import io.cdap.wrangler.api.DirectiveParseException;
import io.cdap.wrangler.api.ExecutorContext;
import io.cdap.wrangler.api.Row;
import io.cdap.wrangler.api.annotations.Categories;
import io.cdap.wrangler.api.parser.ColumnName;
import io.cdap.wrangler.api.parser.Text;
import io.cdap.wrangler.api.parser.TokenType;
import io.cdap.wrangler.api.parser.UsageDefinition;
import com.google.gson.JsonArray;
import com.google.gson.JsonPrimitive;
import com.ximpleware.AutoPilot;
import com.ximpleware.NavException;
import com.ximpleware.VTDNav;
import com.ximpleware.XPathEvalException;
import com.ximpleware.XPathParseException;

import java.util.ArrayList;
import java.util.List;

/**
 * A Executor to extract XML element as an JSON array using XPath.
 *
 * <p>
 *   TODO: This code has to be moved out into a plugin due to VTDNav once we have
 *   the plugin framework.
 * </p>
 *
 */
@Plugin(type = Directive.TYPE)
@Name("xpath-array")
@Categories(categories = { "xml"})
@Description("Extract XML element or attributes as JSON array using XPath.")
public class XPathArrayElement implements Directive {
  public static final String NAME = "xpath-array";
  private String column;
  private String destination;
  private String xpath;
  private String attribute;

  @Override
  public UsageDefinition define() {
    UsageDefinition.Builder builder = UsageDefinition.builder(NAME);
    builder.define("source", TokenType.COLUMN_NAME);
    builder.define("destination", TokenType.COLUMN_NAME);
    builder.define("xpath", TokenType.TEXT);
    return builder.build();
  }

  @Override
  public void initialize(Arguments args) throws DirectiveParseException {
    this.column = ((ColumnName) args.value("source")).value();
    this.destination = ((ColumnName) args.value("destination")).value();
    this.xpath = ((Text) args.value("xpath")).value();
    this.attribute = XPathElement.extractAttributeFromXPath(xpath.trim());
  }

  @Override
  public void destroy() {
    // no-op
  }

  @Override
  public List<Row> execute(List<Row> rows, ExecutorContext context) throws DirectiveExecutionException {
    List<String> values = new ArrayList<>();
    for (Row row : rows) {
      int idx = row.find(column);
      if (idx != -1) {
        Object object = row.getValue(idx);
        if (object instanceof VTDNav) {
          VTDNav vn = (VTDNav) row.getValue(idx);
          AutoPilot ap = new AutoPilot(vn);
          try {
            int tokenCount = vn.getTokenCount();
            String token = null;
            String nsPrefix = null;
            String nsUrl = null;
            for ( int i = 0; i < tokenCount; i++ ) {
              token = vn.toNormalizedString( i );
              if ( vn.startsWith( i, "xmlns:" ) ) {
                nsPrefix = token.substring( token.indexOf( ":" ) + 1 );
                nsUrl = vn.toNormalizedString( i + 1 );
                ap.declareXPathNameSpace( nsPrefix, nsUrl );
              }// if
            }// for
            ap.selectXPath(xpath);
            if (attribute == null) {
              int i = 0, j = 0;
              while (( i = ap.evalXPath()) != -1) {
                int val = vn.getText();
                if (val != -1) {
                  values.add(vn.getXPathStringVal());
                }
              }
              JsonArray array = new JsonArray();
              for (String value : values) {
                array.add(new JsonPrimitive(value));
              }
              row.addOrSet(destination, array);
            } else {
              int i = 0, j = 0;
              while (( i = ap.evalXPath()) != -1) {
                int val = vn.getAttrVal(attribute);
                if (val != -1) {
                  values.add(vn.toString(val));
                }
              }
              JsonArray array = new JsonArray();
              for (String value : values) {
                array.add(new JsonPrimitive(value));
              }
              row.addOrSet(destination, array);
            }
          } catch (XPathParseException | XPathEvalException | NavException e) {
            throw new DirectiveExecutionException(
              String.format("%s : Failed in extracting information using xpath element '%s' for field '%s'. %s",
                            toString(), xpath, column, e.getMessage())
            );
          }
        } else {
          throw new DirectiveExecutionException(
            String.format("%s : Invalid type '%s' of column '%s'. Should be of type parsed XML. 'parse-as-xml' first",
                          toString(), object != null ? object.getClass().getName() : "null", column)
          );
        }
      } else {
        row.addOrSet(destination, null);
      }
    }
    return rows;
  }
}
