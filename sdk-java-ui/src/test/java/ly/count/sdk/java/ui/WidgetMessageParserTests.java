package ly.count.sdk.java.ui;

import ly.count.sdk.java.internal.WidgetAction;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * {@link WidgetPlacementTests#widgetMessageParser_readsOnlyWidgetCommands()} already exercises the
 * parser's boolean and numeric {@code close} shapes; a widget page can also send {@code close} as a
 * quoted JSON string, which {@code isTruthy}'s string fallback has to handle on its own.
 */
@RunWith(JUnit4.class)
public class WidgetMessageParserTests {

    /**
     * {@code close} sent as a JSON string, both a truthy and a non-truthy value, exercises the
     * {@code isTruthy} string fallback that only runs once the value is neither a boolean nor a
     * number.
     */
    @Test
    public void parse_readsCloseSentAsAQuotedString() {
        WidgetAction truthy = WidgetMessageParser.parse("{\"cly_widget_command\":1,\"close\":\"true\"}");
        Assert.assertTrue("a quoted \"true\" must still close the widget", truthy.close);

        WidgetAction truthyOne = WidgetMessageParser.parse("{\"cly_widget_command\":1,\"close\":\"1\"}");
        Assert.assertTrue("a quoted \"1\" must still close the widget", truthyOne.close);

        WidgetAction notTruthy = WidgetMessageParser.parse("{\"cly_widget_command\":1,\"close\":\"nope\"}");
        Assert.assertFalse("any other string must not close the widget", notTruthy.close);
    }
}
