package tinystruct.examples;

import java.awt.image.BufferedImage;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSessionEvent;
import javax.servlet.http.HttpSessionListener;

import org.tinystruct.ApplicationException;
import org.tinystruct.data.component.Builder;
import org.tinystruct.data.component.Builders;
import org.tinystruct.datatype.Variable;
import org.tinystruct.handle.Reforward;
import org.tinystruct.system.util.Matrix;
import org.tinystruct.system.util.StringUtilities;
import org.tinystruct.transfer.http.upload.ContentDisposition;
import org.tinystruct.transfer.http.upload.MultipartFormData;

public class smalltalk extends talk implements HttpSessionListener {
  
  public void init() {
    super.init();

    this.setAction("talk", "index");
    this.setAction("talk/join", "join");
    this.setAction("talk/start", "start");
    this.setAction("talk/upload", "upload");
    this.setAction("talk/command", "command");
    this.setAction("talk/topic", "topic");
    this.setAction("talk/matrix", "matrix");

    this.setVariable("message", "");
    this.setVariable("topic", "");
  }

  public talk index() {
    final HttpServletRequest request = (HttpServletRequest) this.context.getAttribute("HTTP_REQUEST");
    Object meetingCode = request.getSession().getAttribute("meeting_code");

    if ( meetingCode == null ) {
      meetingCode = java.util.UUID.randomUUID().toString();
      request.getSession().setAttribute("meeting_code", meetingCode);

      System.out.println("New meeting generated:" + meetingCode);
    }

    List<String> session_ids;
    synchronized (this.meetings) {
      if (this.meetings.get(meetingCode) == null) {
        this.meetings.put(meetingCode.toString(), new ConcurrentLinkedQueue<Builder>());
      }

      // If the current user is not in the list of the sessions, we create a default session list for the meeting generated.
      if((session_ids = this.sessions.get(meetingCode)) == null)
      {
        this.sessions.put(meetingCode.toString(), session_ids = new ArrayList<String>());
      }

      if(!session_ids.contains(request.getSession().getId()))
      session_ids.add(request.getSession().getId());

      this.meetings.notifyAll();
    }
    
    synchronized (this.list) {
      final String sessionId = request.getSession().getId();
      if(!this.list.containsKey(sessionId))
      {
        this.list.put(sessionId, new ConcurrentLinkedQueue<Builder>());
        this.list.notifyAll();
      }
    }

    this.setVariable("meeting_code", meetingCode.toString());
    this.setVariable("session_id", request.getSession().getId());

    Variable<?> topic;
    if ((topic = this.getVariable(meetingCode.toString())) != null) {
      this.setVariable("topic", topic.getValue().toString().replaceAll("[\r\n]", "<br />"), true);
    }

    return this;
  }

  public String matrix() throws ApplicationException {
    final HttpServletRequest request = (HttpServletRequest) this.context.getAttribute("HTTP_REQUEST");

    if (request.getParameter("meeting_code") != null) {
      BufferedImage qrImage = Matrix.toQRImage(this.getLink("talk/join") + "/" + request.getParameter("meeting_code"), 100, 100);
      return "data:image/png;base64," + Matrix.getBase64Image(qrImage);
    }

    return "";
  }

  public String join(String meetingCode) throws ApplicationException {
    if (meetings.containsKey(meetingCode)) {
      final HttpServletRequest request = (HttpServletRequest) this.context.getAttribute("HTTP_REQUEST");
      final HttpServletResponse response = (HttpServletResponse) this.context.getAttribute("HTTP_RESPONSE");
      request.getSession().setAttribute("meeting_code", meetingCode);

      this.setVariable("meeting_code", meetingCode);

      Reforward reforward = new Reforward(request, response);
      reforward.setDefault("/?q=talk");
      reforward.forward();
    } else {
      return "Invalid meeting code.";
    }

    return "Please start the conversation with your name: " + this.config.get("default.base_url") + "talk/start/YOUR NAME";
  }

  public String start(String name) throws ApplicationException {
    final HttpServletRequest request = (HttpServletRequest) this.context.getAttribute("HTTP_REQUEST");
    final HttpServletResponse response = (HttpServletResponse) this.context.getAttribute("HTTP_RESPONSE");

    Object meetingCode = request.getSession().getAttribute("meeting_code");
    if (meetingCode == null) {
      Reforward reforward = new Reforward(request, response);
      reforward.setDefault("/?q=talk");
      reforward.forward();
    } else {
      this.setVariable("meeting_code", meetingCode.toString());
    }
    request.getSession().setAttribute("user", name);

    return name;
  }

  public String command() {
    final HttpServletRequest request = (HttpServletRequest) this.context.getAttribute("HTTP_REQUEST");
    final HttpServletResponse response = (HttpServletResponse) this.context.getAttribute("HTTP_RESPONSE");
    response.setContentType("application/json");

    final Object meetingCode = request.getSession().getAttribute("meeting_code");
    final String sessionId = request.getSession().getId();
    if ( meetingCode != null && sessions.get(meetingCode) != null && sessions.get(meetingCode).contains(sessionId)) {
      if (request.getSession().getAttribute("user") == null) {
        return "{ \"error\": \"missing user\" }";
      }

      Builder builder = new Builder();
      builder.put("user", request.getSession().getAttribute("user"));
      builder.put("cmd", request.getParameter("cmd"));

      return this.save(meetingCode, builder);
    }

    return "{ \"error\": \"expired\" }";
  }

  public String save() {
    final HttpServletRequest request = (HttpServletRequest) this.context.getAttribute("HTTP_REQUEST");
    final HttpServletResponse response = (HttpServletResponse) this.context.getAttribute("HTTP_RESPONSE");
    response.setContentType("application/json");

    final Object meetingCode = request.getSession().getAttribute("meeting_code");
    final String sessionId = request.getSession().getId();
    if ( meetingCode != null && sessions.get(meetingCode) != null && sessions.get(meetingCode).contains(sessionId)) {
      String message;
      if ((message = request.getParameter("text")) != null && !message.isEmpty()) {
        String[] agent = request.getHeader("User-Agent").split(" ");
        this.setVariable("browser", agent[agent.length - 1]);

        final SimpleDateFormat format = new SimpleDateFormat("yyyy-M-d h:m:s");
        final Builder builder = new Builder();
        builder.put("user", request.getSession().getAttribute("user"));
        builder.put("time", format.format(new Date()));
        builder.put("message", filter(message));
        builder.put("session_id", sessionId);

        return this.save(meetingCode, builder);
      }
    }

    return "{}";
  }

  public String update() throws ApplicationException, IOException {
    final HttpServletRequest request = (HttpServletRequest) this.context.getAttribute("HTTP_REQUEST");
    final Object meetingCode = request.getSession().getAttribute("meeting_code");
    final String sessionId = request.getSession().getId();
    if ( meetingCode != null && sessions.get(meetingCode) != null && sessions.get(meetingCode).contains(sessionId)) {
      return update(sessionId);
    }
    return "";
  }

  public String update(String meetingCode, String sessionId) throws ApplicationException, IOException {
    if ( meetingCode != null && sessions.get(meetingCode) != null && sessions.get(meetingCode).contains(sessionId)) {
      return update(sessionId);
    }
    return "";
  }

  public String upload() throws ApplicationException {
    final HttpServletRequest request = (HttpServletRequest) this.context.getAttribute("HTTP_REQUEST");
    final HttpServletResponse response = (HttpServletResponse) this.context.getAttribute("HTTP_RESPONSE");
    response.setContentType("text/html;charset=UTF-8");

    // Create path components to save the file
    final String path = this.config.get("system.directory") != null ? this.config.get("system.directory").toString() + "/files" : "files";

    final Builders builders = new Builders();
    try {
      final MultipartFormData iter = new MultipartFormData(request);
      ContentDisposition e = null;
      int read = 0;
      while ((e = iter.getNextPart()) != null) {
        final String fileName = e.getFileName();
        final Builder builder = new Builder();
        builder.put("type", StringUtilities.implode(";", Arrays.asList(e.getContentType())));
        builder.put("file", new StringBuffer().append(this.context.getAttribute("HTTP_SCHEME")).append("://").append(this.context.getAttribute("HTTP_SERVER")).append(":"+ request.getServerPort()).append( "/files/").append(fileName));
        final File f = new File(path + File.separator + fileName);
        if (!f.exists()) {
          if (!f.getParentFile().exists()) {
            f.getParentFile().mkdirs();
          }
        }

        final OutputStream out = new FileOutputStream(f);
        final BufferedOutputStream bout= new BufferedOutputStream(out);
        final ByteArrayInputStream is = new ByteArrayInputStream(e.getData());
        final BufferedInputStream bs = new BufferedInputStream(is);
        final byte[] bytes = new byte[8192];
        while ((read = bs.read(bytes)) != -1) {
           bout.write(bytes, 0, read);
        }
        bout.close();
        bs.close();

        builders.add(builder);
        System.out.println(String.format("File %s being uploaded to %s", new Object[] { fileName, path }));
      }
    } catch (IOException e) {
      throw new ApplicationException(e.getMessage(), e);
    } catch (ServletException e) {
      throw new ApplicationException(e.getMessage(), e);
    }

    return builders.toString();
  }

  public boolean topic() {
    final HttpServletRequest request = (HttpServletRequest) this.context.getAttribute("HTTP_REQUEST");
    final Object meeting_code = request.getSession().getAttribute("meeting_code");

    if ( meeting_code != null ) {
      this.setVariable(meeting_code.toString(), filter(request.getParameter("topic")));
      return true;
    }

    return false;
  }

  protected talk exit() {
    final HttpServletRequest request = (HttpServletRequest) this.context.getAttribute("HTTP_REQUEST");
    request.getSession().removeAttribute("meeting_code");
    return this;
  }

  @Override
  protected String filter(String text) {
    text = text.replaceAll("<script(.*)>(.*)<\\/script>", "");
    return text;
  }

  @Override
  public void sessionCreated(HttpSessionEvent arg0) {
    Object meetingCode = arg0.getSession().getAttribute("meeting_code");
    if ( meetingCode == null ) {
      meetingCode = java.util.UUID.randomUUID().toString();
      arg0.getSession().setAttribute("meeting_code", meetingCode);

      System.out.println("New meeting generated by HttpSessionListener:" + meetingCode);
    }

    synchronized (this.list) {
      final String sessionId = arg0.getSession().getId();
      if(!this.list.containsKey(sessionId))
      {
        this.list.put(sessionId, new ConcurrentLinkedQueue<Builder>());
        this.list.notifyAll();
      }
    }
  }

  @Override
  public void sessionDestroyed(HttpSessionEvent arg0) {
    Object meetingCode = arg0.getSession().getAttribute("meeting_code");
    if ( meetingCode != null ) {
      Queue<Builder> messages;
      List<String> session_ids;
      synchronized (meetings) {
        if((session_ids = this.sessions.get(meetingCode)) != null)
        {
          session_ids.remove(arg0.getSession().getId());
        }
        if ((messages = meetings.get(meetingCode)) != null) {
          messages.remove(meetingCode);
          meetings.notifyAll();
        }
      }

      synchronized (this.list) {
        final String sessionId = arg0.getSession().getId();
        if(this.list.containsKey(sessionId))
        {
          this.list.remove(sessionId);
          this.list.notifyAll();
        }
      }
    }
  }
}
