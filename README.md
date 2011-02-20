# Example Configuration

You'll need to fill in the AWS keys, of course. Another thing to keep in mind is if you're sandboxed you'll need to verify the addresses you're sending to.

    <?xml version="1.0" encoding="UTF-8" ?>
    <!DOCTYPE log4j:configuration PUBLIC "-//LOGGER" "http://logging.apache.org/log4j/1.2/apidocs/org/apache/log4j/xml/doc-files/log4j.dtd">
    <log4j:configuration xmlns:log4j="http://jakarta.apache.org/log4j/">

      <appender name="console" class="org.apache.log4j.ConsoleAppender"> 
        <param name="target" value="System.out"/> 
        <layout class="org.apache.log4j.PatternLayout"> 
          <param name="ConversionPattern" value="%-5p %c{1} - %m%n"/> 
        </layout> 
      </appender> 

      <appender name="ses" class="com.github.jlewallen.logging.appenders.aws.AmazonSESAppender"> 
        <param name="bufferSize" value="10" />
        <param name="to" value="nobody+ses@gmail.com" />
        <param name="from" value="nobody+ses@gmail.com" />
        <param name="subject" value="[ERROR] %c{1}: %m" />
        <param name="AWSAccessKeyId" value="****" />
        <param name="AWSSecretKey" value="****" />
        <triggeringPolicy class="com.github.jlewallen.logging.ThrottledEventEvaluator">
    	    <param name="maximumPerInterval" value="3" />
    	    <param name="intervalLength" value="500" />
        </triggeringPolicy>
        <layout class="org.apache.log4j.PatternLayout"> 
          <param name="ConversionPattern" value="%d{ISO8601} %-5p (%F:%L) - %m%n"/> 
        </layout> 
      </appender> 

      <root> 
        <priority value ="info" /> 
        <appender-ref ref="console" /> 
        <appender-ref ref="ses" /> 
      </root>
  
    </log4j:configuration>
