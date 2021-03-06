package com.github.jlewallen.logging.appenders.aws;

import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.io.UnsupportedEncodingException;
import java.io.Writer;
import java.util.Date;
import java.util.Properties;

import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Multipart;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.AddressException;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.InternetHeaders;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;
import javax.mail.internet.MimeUtility;

import org.apache.log4j.AppenderSkeleton;
import org.apache.log4j.Layout;
import org.apache.log4j.Level;
import org.apache.log4j.PatternLayout;
import org.apache.log4j.helpers.CyclicBuffer;
import org.apache.log4j.helpers.LogLog;
import org.apache.log4j.helpers.OptionConverter;
import org.apache.log4j.spi.ErrorCode;
import org.apache.log4j.spi.LoggingEvent;
import org.apache.log4j.spi.OptionHandler;
import org.apache.log4j.spi.TriggeringEventEvaluator;
import org.apache.log4j.xml.UnrecognizedElementHandler;
import org.w3c.dom.Element;

import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.services.simpleemail.AWSJavaMailTransport;

public class AmazonSESAppender extends AppenderSkeleton implements AWSCredentials, UnrecognizedElementHandler {

   private int bufferSize = 512;
   private CyclicBuffer cyclicBuffer = new CyclicBuffer(bufferSize);
   private Message message;
   private Transport transport;
   private TriggeringEventEvaluator evaluator;

   private String to;
   private String cc;
   private String bcc;
   private String from;
   private String replyTo;
   private String subject;
   private String secretKey;
   private String accessKeyId;
   private boolean locationInfo = false;
   private boolean sendOnClose = false;

   public AmazonSESAppender() {
      this(new DefaultEvaluator());
   }

   public AmazonSESAppender(TriggeringEventEvaluator evaluator) {
      this.evaluator = evaluator;
   }

   protected Session createSession() {
      Properties properties = new Properties();
      properties.setProperty("mail.transport.protocol", "aws");
      if(getAWSAccessKeyId() != null) {
         properties.setProperty("mail.aws.user", getAWSAccessKeyId());
      }
      if(getAWSSecretKey() != null) {
         properties.setProperty("mail.aws.password", getAWSSecretKey());
      }
      return Session.getInstance(properties);
   }

   /**
    * Activate the specified options
    */
   public void activateOptions() {
      Session session = createSession();
      transport = new AWSJavaMailTransport(session, null);
      message = new MimeMessage(session);

      try {
         addressMessage(message);
         if(subject != null) {
            try {
               message.setSubject(MimeUtility.encodeText(subject, "UTF-8", null));
            }
            catch(UnsupportedEncodingException ex) {
               LogLog.error("Unable to encode SMTP subject", ex);
            }
         }
      }
      catch(MessagingException e) {
         LogLog.error("Could not activate SMTPAppender options.", e);
      }

      if(evaluator instanceof OptionHandler) {
         ((OptionHandler)evaluator).activateOptions();
      }
   }

   protected void addressMessage(final Message msg) throws MessagingException {
      if(from != null) {
         msg.setFrom(getAddress(from));
      }
      else {
         msg.setFrom();
      }
      if(replyTo != null && replyTo.length() > 0) {
         msg.setReplyTo(parseAddress(replyTo));
      }
      if(to != null && to.length() > 0) {
         msg.setRecipients(Message.RecipientType.TO, parseAddress(to));
      }
      if(cc != null && cc.length() > 0) {
         msg.setRecipients(Message.RecipientType.CC, parseAddress(cc));
      }
      if(bcc != null && bcc.length() > 0) {
         msg.setRecipients(Message.RecipientType.BCC, parseAddress(bcc));
      }
   }

   public boolean parseUnrecognizedElement(final Element element, final Properties props) throws Exception {
      if("triggeringPolicy".equals(element.getNodeName())) {
         Object triggerPolicy = org.apache.log4j.xml.DOMConfigurator.parseElement(element, props, TriggeringEventEvaluator.class);
         if(triggerPolicy instanceof TriggeringEventEvaluator) {
            setEvaluator((TriggeringEventEvaluator)triggerPolicy);
         }
         return true;
      }
      return false;
   }

   InternetAddress getAddress(String addressStr) {
      try {
         return new InternetAddress(addressStr);
      }
      catch(AddressException e) {
         errorHandler.error("Could not parse address [" + addressStr + "].", e, ErrorCode.ADDRESS_PARSE_FAILURE);
         return null;
      }
   }

   InternetAddress[] parseAddress(String addressStr) {
      try {
         return InternetAddress.parse(addressStr, true);
      }
      catch(AddressException e) {
         errorHandler.error("Could not parse address [" + addressStr + "].", e, ErrorCode.ADDRESS_PARSE_FAILURE);
         return null;
      }
   }

   static class BodyAndSubject {
      public String body;
      public String subject;
   }

   protected BodyAndSubject formatBodyAndSubject() throws UnsupportedEncodingException {
      // Note: this code already owns the monitor for this
      // appender. This frees us from needing to synchronize on 'cb'.
      BodyAndSubject bodyAndSubject = new BodyAndSubject();
      StringBuffer sbuf = new StringBuffer();
      String header = layout.getHeader();
      if(header != null) {
         sbuf.append(header);
      }
      int length = cyclicBuffer.length();
      for(int i = 0; i < length; i++) {
         LoggingEvent event = cyclicBuffer.get();
         sbuf.append(layout.format(event));
         if(layout.ignoresThrowable()) {
            String[] throwable = event.getThrowableStrRep();
            if(throwable != null) {
               for(int j = 0; j < throwable.length; j++) {
                  sbuf.append(throwable[j]);
                  sbuf.append(Layout.LINE_SEP);
               }
            }
         }
         if((i == length - 1 || event.getThrowableInformation() != null) && bodyAndSubject.subject == null) {
            Layout subjectLayout = new PatternLayout(getSubject());
            bodyAndSubject.subject = MimeUtility.encodeText(subjectLayout.format(event), "UTF-8", null);
         }
      }
      String footer = layout.getFooter();
      if(footer != null) {
         sbuf.append(footer);
      }
      if(bodyAndSubject.subject == null) {
         bodyAndSubject.subject = getSubject();

      }
      bodyAndSubject.body = sbuf.toString();
      return bodyAndSubject;
   }

   /**
    * Send the contents of the cyclic buffer as an e-mail message.
    */
   protected void sendBuffer() {
      try {
         BodyAndSubject bodyAndSubject = formatBodyAndSubject();
         String body = bodyAndSubject.body;
         boolean allAscii = true;
         for(int i = 0; i < body.length() && allAscii; i++) {
            allAscii = body.charAt(i) <= 0x7F;
         }
         MimeBodyPart part;
         if(allAscii) {
            part = new MimeBodyPart();
            part.setContent(body, layout.getContentType());
         }
         else {
            try {
               ByteArrayOutputStream os = new ByteArrayOutputStream();
               Writer writer = new OutputStreamWriter(MimeUtility.encode(os, "quoted-printable"), "UTF-8");
               writer.write(body);
               writer.close();
               InternetHeaders headers = new InternetHeaders();
               headers.setHeader("Content-Type", layout.getContentType() + "; charset=UTF-8");
               headers.setHeader("Content-Transfer-Encoding", "quoted-printable");
               part = new MimeBodyPart(headers, os.toByteArray());
            }
            catch(Exception ex) {
               StringBuffer sbuf = new StringBuffer(body);
               for(int i = 0; i < sbuf.length(); i++) {
                  if(sbuf.charAt(i) >= 0x80) {
                     sbuf.setCharAt(i, '?');
                  }
               }
               part = new MimeBodyPart();
               part.setContent(sbuf.toString(), layout.getContentType());
            }
         }

         Multipart multipart = new MimeMultipart();
         multipart.addBodyPart(part);
         message.setSubject(bodyAndSubject.subject);
         message.setContent(multipart);
         message.setSentDate(new Date());

         if(!transport.isConnected()) {
            transport.connect();
         }
         transport.sendMessage(message, null);
         transport.close();
      }
      catch(MessagingException e) {
         LogLog.error("Error occured while sending e-mail notification.", e);
      }
      catch(RuntimeException e) {
         LogLog.error("Error occured while sending e-mail notification.", e);
      }
      catch(UnsupportedEncodingException e) {
         LogLog.error("Error occured while sending e-mail notification.", e);
      }
   }

   /**
    * This method determines if there is a sense in attempting to append.
    * <p>
    * It checks whether there is a set output target and also if there is a set layout. If these checks fail, then the
    * boolean value <code>false</code> is returned.
    */
   protected boolean checkEntryConditions() {
      if(this.accessKeyId == null) {
         errorHandler.error("No AWS AccessKeyId is configured.");
         return false;
      }
      if(this.secretKey == null) {
         errorHandler.error("No AWS SecretKey is configured.");
         return false;
      }
      if(this.message == null) {
         errorHandler.error("Message object not configured.");
         return false;
      }
      if(this.evaluator == null) {
         errorHandler.error("No TriggeringEventEvaluator is set for appender [" + name + "].");
         return false;
      }
      if(this.layout == null) {
         errorHandler.error("No layout set for appender named [" + name + "].");
         return false;
      }
      return true;
   }

   synchronized public void close() {
      this.closed = true;
      if(sendOnClose && cyclicBuffer.length() > 0) {
         sendBuffer();
      }
   }

   public boolean requiresLayout() {
      return true;
   }

   /**
    * Perform SMTPAppender specific appending actions, mainly adding the event to a cyclic buffer and checking if the
    * event triggers an e-mail to be sent.
    */
   public void append(LoggingEvent event) {
      if(!checkEntryConditions()) {
         return;
      }

      event.getThreadName();
      event.getNDC();
      event.getMDCCopy();
      if(locationInfo) {
         event.getLocationInformation();
      }
      event.getRenderedMessage();
      event.getThrowableStrRep();
      cyclicBuffer.add(event);
      if(evaluator.isTriggeringEvent(event)) {
         sendBuffer();
      }
   }

   /**
    * Returns value of the <b>EvaluatorClass</b> option.
    */
   public String getEvaluatorClass() {
      return evaluator == null ? null : evaluator.getClass().getName();
   }

   /**
    * The <b>EvaluatorClass</b> option takes a string value representing the name of the class implementing the
    * {@link TriggeringEventEvaluator} interface. A corresponding object will be instantiated and assigned as the
    * triggering event evaluator for the SMTPAppender.
    */
   public void setEvaluatorClass(String value) {
      evaluator = (TriggeringEventEvaluator)OptionConverter.instantiateByClassName(value, TriggeringEventEvaluator.class, evaluator);
   }

   public String getAWSSecretKey() {
      return secretKey;
   }

   public void setAWSSecretKey(String secretKey) {
      this.secretKey = secretKey;
   }

   public String getAWSAccessKeyId() {
      return this.accessKeyId;
   }

   public void setAWSAccessKeyId(String accessKeyId) {
      this.accessKeyId = accessKeyId;
   }

   /**
    * Returns value of the <b>From</b> option.
    */
   public String getFrom() {
      return from;
   }

   /**
    * The <b>From</b> option takes a string value which should be a e-mail address of the sender.
    */
   public void setFrom(String from) {
      this.from = from;
   }

   /**
    * Get the reply addresses.
    * 
    * @return reply addresses as comma separated string, may be null.
    * @since 1.2.16
    */
   public String getReplyTo() {
      return replyTo;
   }

   /**
    * Set the e-mail addresses to which replies should be directed.
    * 
    * @param addresses
    *           reply addresses as comma separated string, may be null.
    * @since 1.2.16
    */
   public void setReplyTo(final String addresses) {
      this.replyTo = addresses;
   }

   /**
    * Returns value of the <b>Subject</b> option.
    */
   public String getSubject() {
      return subject;
   }

   /**
    * The <b>Subject</b> option takes a string value which should be a the subject of the e-mail message.
    */
   public void setSubject(String subject) {
      this.subject = subject;
   }

   /**
    * Returns value of the <b>BufferSize</b> option.
    */
   public int getBufferSize() {
      return bufferSize;
   }

   /**
    * The <b>BufferSize</b> option takes a positive integer representing the maximum number of logging events to collect
    * in a cyclic buffer. When the <code>BufferSize</code> is reached, oldest events are deleted as new events are added
    * to the buffer. By default the size of the cyclic buffer is 512 events.
    */
   public void setBufferSize(int bufferSize) {
      this.bufferSize = bufferSize;
      cyclicBuffer.resize(bufferSize);
   }

   /**
    * Returns value of the <b>To</b> option.
    */
   public String getTo() {
      return to;
   }

   /**
    * The <b>To</b> option takes a string value which should be a comma separated list of e-mail address of the
    * recipients.
    */
   public void setTo(String to) {
      this.to = to;
   }

   /**
    * Returns value of the <b>LocationInfo</b> option.
    */
   public boolean getLocationInfo() {
      return locationInfo;
   }

   /**
    * The <b>LocationInfo</b> option takes a boolean value. By default, it is set to false which means there will be no
    * effort to extract the location information related to the event. As a result, the layout that formats the events
    * as they are sent out in an e-mail is likely to place the wrong location information (if present in the format).
    * <p>
    * Location information extraction is comparatively very slow and should be avoided unless performance is not a
    * concern.
    */
   public void setLocationInfo(boolean locationInfo) {
      this.locationInfo = locationInfo;
   }

   /**
    * Get the cc recipient addresses.
    * 
    * @return recipient addresses as comma separated string, may be null.
    * @since 1.2.14
    */
   public String getCc() {
      return cc;
   }

   /**
    * Set the cc recipient addresses.
    * 
    * @param addresses
    *           recipient addresses as comma separated string, may be null.
    * @since 1.2.14
    */
   public void setCc(final String addresses) {
      this.cc = addresses;
   }

   /**
    * Get the bcc recipient addresses.
    * 
    * @return recipient addresses as comma separated string, may be null.
    * @since 1.2.14
    */
   public String getBcc() {
      return bcc;
   }

   /**
    * Set the bcc recipient addresses.
    * 
    * @param addresses
    *           recipient addresses as comma separated string, may be null.
    * @since 1.2.14
    */
   public void setBcc(final String addresses) {
      this.bcc = addresses;
   }

   /**
    * Sets triggering evaluator.
    * 
    * @param trigger
    *           triggering event evaluator.
    * @since 1.2.15
    */
   public final void setEvaluator(final TriggeringEventEvaluator trigger) {
      if(trigger == null) {
         throw new NullPointerException("trigger");
      }
      this.evaluator = trigger;
   }

   /**
    * Get triggering evaluator.
    * 
    * @return triggering event evaluator.
    * @since 1.2.15
    */
   public final TriggeringEventEvaluator getEvaluator() {
      return evaluator;
   }

   /**
    * Get sendOnClose.
    * 
    * @return if true all buffered logging events will be sent when the appender is closed.
    * @since 1.2.16
    */
   public final boolean getSendOnClose() {
      return sendOnClose;
   }

   /**
    * Set sendOnClose.
    * 
    * @param val
    *           if true all buffered logging events will be sent when appender is closed.
    * @since 1.2.16
    */
   public final void setSendOnClose(final boolean val) {
      sendOnClose = val;
   }

}

class DefaultEvaluator implements TriggeringEventEvaluator {
   /**
    * Is this <code>event</code> the e-mail triggering event?
    * <p>
    * This method returns <code>true</code>, if the event level has ERROR level or higher. Otherwise it returns
    * <code>false</code>.
    */
   public boolean isTriggeringEvent(LoggingEvent event) {
      return event.getLevel().isGreaterOrEqual(Level.ERROR);
   }
}
