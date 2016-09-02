package fr.delthas.skype;

import fr.delthas.skype.MessageListener.MessageEvent;
import fr.delthas.skype.message.*;
import org.jsoup.Jsoup;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.net.ssl.SSLSocketFactory;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static fr.delthas.skype.Constants.HEADER_RICH_TEXT;
import static fr.delthas.skype.Constants.HEADER_TEXT;

class NotifConnector {

  private static class Packet {

    public final String command;
    public final String params;
    public final String body;

    public Packet(String command, String params, String body) {
      this.command = command;
      this.params = params;
      this.body = body;
    }

    @Override
    public String toString() {
      return String.format("Command: %s Params: %s Body: %s", command, params, body);
    }

  }

  private static final Logger logger = Logger.getLogger("fr.delthas.skype.notif");
  private static final String EPID = generateEPID(); // generate EPID at runtime
  private static final String DEFAULT_SERVER_HOSTNAME = "s.gateway.messenger.live.com";
  private static final int DEFAULT_SERVER_PORT = 443;
  private static final Pattern patternFirstLine = Pattern.compile("([A-Z]+|\\d+) \\d+ ([A-Z]+(?:\\\\[A-Z]+)?) (\\d+)");
  private static final Pattern patternHeaders = Pattern.compile("\\A(?:(?:Set-Registration: (.+)|[A-Za-z\\-]+: .+)\\R)*\\R");
  private static final Pattern patternXFR = Pattern.compile("([a-zA-Z0-9\\.\\-]+):(\\d+)");
  private static final long pingInterval = 2 * 60000000000L; // minutes
  private long lastMessageSentTime;
  private Thread pingThread;
  private final DocumentBuilder documentBuilder;
  private final Skype skype;
  private final String username, password;
  private boolean disconnectRequested = false;
  private Socket socket;
  private BufferedWriter writer;
  private BufferedInputStream inputStream;
  private int sequenceNumber;
  private String registration;
  private CountDownLatch connectLatch = new CountDownLatch(1);

  public NotifConnector(Skype skype, String username, String password) {
    this.skype = skype;
    this.username = username;
    this.password = password;
    try {
      documentBuilder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
    } catch (ParserConfigurationException e) {
      // Should never happen, throw RE if it does
      throw new RuntimeException(e);
    }
  }

  private void processPacket(Packet packet) throws IOException {
    logger.finer("Received packet " + packet.command + " " + packet.params);
    logger.finest("Recieved packet body: " + packet.body);
    switch (packet.command) {
      case "GET":
        if (packet.params.equals("MSGR")) {
          Document doc;
          try {
            doc = getDocument(packet.body);
          } catch (ParseException e) {
            logger.log(Level.FINE, "Error while parsing GET MSGR message", e);
            break;
          }
          String mainNode = doc.getFirstChild().getNodeName();
          switch (mainNode) {
            case "recentconversations-response":
              NodeList conversationNodes = doc.getElementsByTagName("conversation");
              List<String> threadIds = new ArrayList<>();
              outer:
              for (int i = 0; i < conversationNodes.getLength(); i++) {
                Node conversation = conversationNodes.item(i);
                String id = null;
                boolean isThread = false;
                NodeList conversationChildren = conversation.getChildNodes();
                for (int j = 0; j < conversationChildren.getLength(); j++) {
                  Node child = conversationChildren.item(j);
                  if (child.getNodeName().equals("id")) {
                    id = child.getTextContent();
                  } else if (child.getNodeName().equals("thread")) {
                    isThread = true;
                    NodeList threadNodes = child.getChildNodes();
                    for (int k = 0; k < threadNodes.getLength(); k++) {
                      Node threadNode = threadNodes.item(k);
                      // if there's a node named "lastleaveat", we've left this group
                      // do not show it in our group list
                      if (threadNode.getNodeName().equals("lastleaveat")) {
                        continue outer;
                      }
                    }
                  } else if (child.getNodeName().equals("messages")) {
                    if (!child.hasChildNodes()) {
                      // messages is empty, this probably means we've left this group
                      // do not show it in our group list
                      continue outer;
                    }
                  }
                }
                if (id == null || !isThread) {
                  continue outer;
                }
                Group group = (Group) parseEntity(id);
                threadIds.add(group.getId());
              }
              if (!threadIds.isEmpty()) {
                logger.finest("Fetching threads information");
                StringBuilder sb = new StringBuilder("<threads>");
                for (String threadId : threadIds) {
                  if (sb.length() > 30000) {
                    String body = sb.append("</threads>").toString();
                    sendPacket("GET", "MSGR\\THREADS", body);
                    sb.delete(0, sb.length());
                    sb.append("<threads>");
                  }
                  sb.append("<thread><id>").append(threadId).append("</id></thread>");
                }
                String body = sb.append("</threads>").toString();
                sendPacket("GET", "MSGR\\THREADS", body);
                sendPacket("PNG", "CON", "");
              } else {
                logger.finer("No threads received in recentconversations-response");
                logger.fine("Connected! Stopped blocking.");
                connectLatch.countDown(); // stop blocking: we're connected
              }
              break;
            case "threads-response":
              NodeList threadNodes = doc.getElementsByTagName("thread");
              for (int i = 0; i < threadNodes.getLength(); i++) {
                updateThread(threadNodes.item(i));
              }
              break;
            default:
          }
        }
        break;
      case "SDG":
        if (!packet.body.isEmpty()) {
          FormattedMessage formatted;
          try {
            formatted = FormattedMessage.parseMessage(packet.body);
          } catch (IllegalArgumentException e) {
            // weird message with no interesting content
            logger.log(Level.FINE, "Couldn't parse SDG formatted message", e);
            break;
          }
          String messageType = formatted.headers.get("Message-Type");
          if (messageType == null) {
            break;
          }
          Object sender = parseEntity(formatted.sender);
          Object receiver = parseEntity(formatted.receiver);
          if (messageType.startsWith(HEADER_RICH_TEXT) || messageType.startsWith(HEADER_TEXT)) {
            // work with messages
            if (!(sender instanceof User)) {
              logger.fine("Received " + messageType + " message sent from " + sender + " which isn't a user");
              break;
            }
            boolean isRemoved = false;

            String isMe = formatted.headers.get("Skype-EmoteOffset");
            String messageId = formatted.headers.get("Client-Message-ID");
            String editId = formatted.headers.get("Skype-EditedId");
            String html = formatted.body;
            if (isMe != null) {
              html = formatted.body.substring(Integer.parseInt(isMe));
            }
            if (editId != null) {
              String editOffset = formatted.headers.get("Skype-EditOffset");
              if (editOffset != null) {
                Integer offset = Integer.parseInt(editOffset);
                html = html.substring(offset);
              }
              html = html.replaceAll("<e_m.*t=\".*\"\\/>", "");
              isRemoved = html.isEmpty();
            }
            String id = messageId != null ? messageId : editId;
            Message message = null;
            MessageType type = MessageType.getTypeByHeaderType((messageType));
            if (type == null) {
              break;
            }
            switch (type) {
              case TEXT:
                String singleRow = html.replaceAll("\r", "").replaceAll("\n", "");
                Boolean isHasQuotes = Pattern.compile(".*<quote.*>.*<\\/quote>.*").matcher(singleRow).matches();
                message = new TextMessage(id, html, isMe != null, isHasQuotes);
                break;
              case PICTURE:
                message = new PictureMessage(id, html);
                break;
              case FILE:
                message = new FileMessage(id, html);
                break;
              case VIDEO:
                message = new VideoMessage(id, html);
                break;
              case CONTACT:
                message = new ContactMessage(id, html);
                break;
              case MOJI:
                message = new MojiMessage(id, html);
                break;
              case UNKNOWN:
                message = new UnknownMessage(id, html);
                break;
              default:
                break;
            }
            MessageEvent eventType = editId == null ? MessageEvent.RECEIVED : isRemoved ? MessageEvent.REMOVED : MessageEvent.EDITED;
            if (receiver instanceof Group) {
              skype.doGroupMessageEvent(eventType, (Group) receiver, (User) sender, message);
            } else {
              skype.doUserMessageEvent(eventType, (User) sender, message);
            }
          } else {
            switch (messageType) {
              case "ThreadActivity/AddMember":
                List<String> usernames = getXMLFields(formatted.body, "target");
                skype.usersAddedToGroup(usernames.stream().map(username -> (User) parseEntity(username)).collect(Collectors.toList()), (Group) sender);
                break;
              case "ThreadActivity/DeleteMember":
                usernames = getXMLFields(formatted.body, "target");
                skype.usersRemovedFromGroup(usernames.stream().map(username -> (User) parseEntity(username)).collect(Collectors.toList()), (Group) sender);
                break;
              case "ThreadActivity/TopicUpdate":
                skype.groupTopicChanged((Group) sender, getPlaintext(getXMLField(formatted.body, "value")));
                break;
              case "ThreadActivity/RoleUpdate":
                Document doc = getDocument(formatted.body);
                NodeList targetNodes = doc.getElementsByTagName("target");
                List<Pair<User, Role>> roles = new ArrayList<>(targetNodes.getLength());
                for (int i = 0; i < targetNodes.getLength(); i++) {
                  Node targetNode = targetNodes.item(i);
                  User user = null;
                  Role role = null;
                  for (int j = 0; j < targetNode.getChildNodes().getLength(); j++) {
                    Node targetPropertyNode = targetNode.getChildNodes().item(j);
                    if (targetPropertyNode.getNodeName().equals("id")) {
                      user = (User) parseEntity(targetPropertyNode.getTextContent());
                      skype.updateUser(user);
                    } else if (targetPropertyNode.getNodeName().equals("role")) {
                      role = Role.getRole(targetPropertyNode.getTextContent());
                    }
                  }
                  if (user != null && role != null) {
                    roles.add(new Pair<>(user, role));
                  }
                }
                skype.usersRolesChanged((Group) sender, roles);
                break;
              default:
                break;
            }
          }

        }
        break;
      case "NFY":
        switch (packet.params) {
          case "MSGR\\DEL":
            FormattedMessage formatted = FormattedMessage.parseMessage(packet.body);
            ((User) parseEntity(formatted.sender)).setPresence(Presence.OFFLINE);
            break;
          case "MSGR\\PUT":
            formatted = FormattedMessage.parseMessage(packet.body);
            User user = (User) parseEntity(formatted.sender);
            String presenceString = getXMLField(formatted.body, "Status");
            if (presenceString == null) {
              // happens when a user switches from offline to "hidden"
              presenceString = Presence.OFFLINE.getPresenceString();
            }
            user.setPresence(presenceString);
            String moodString = getXMLField(formatted.body, "Mood");
            if (moodString != null) {
              user.setMood(getPlaintext(moodString));
            }
            break;
          case "MSGR\\THREAD":
            Document document = getDocument(packet.body);
            updateThread(document);
            break;
          default:
            break;
        }
        break;
      case "XFR":
        String newAddress = getXMLField(packet.body, "target");
        if (newAddress == null) {
          ParseException e = new ParseException("Received XFR message without target address");
          logger.log(Level.SEVERE, "", e);
          throw e;
        }
        Matcher matcherXFR = patternXFR.matcher(newAddress);
        if (!matcherXFR.matches()) {
          ParseException e = new ParseException("Received XFR message with target not matching pattern: address: " + newAddress);
          logger.log(Level.SEVERE, "", e);
          throw e;
        }
        String hostname = matcherXFR.group(1);
        String portString = matcherXFR.group(2);
        int port;
        try {
          port = Integer.parseUnsignedInt(portString);
        } catch (NumberFormatException e_) {
          ParseException e = new ParseException("Couldn't parse port from XFR target: address: " + newAddress + " portString:" + portString, e_);
          logger.log(Level.SEVERE, "", e);
          throw e;
        }
        connectTo(hostname, port);
        break;
      case "CNT":
        String nonce = getXMLField(packet.body, "nonce");
        if (nonce == null) {
          ParseException e = new ParseException("No nonce received in CNT message! Cannot compute UIC");
          logger.log(Level.SEVERE, "", e);
          throw e;
        }
        String uic;
        try {
          uic = UicConnector.getUIC(username, password, nonce);
        } catch (GeneralSecurityException e) {
          logger.log(Level.SEVERE, "Error when computing UIC token", e);
          throw new RuntimeException(e);
        }
        sendPacket("ATH", "CON\\USER", "<user><uic>" + uic + "</uic><id>" + username + "</id></user>");
        break;
      case "ATH":
        sendPacket("BND", "CON\\MSGR",
            "<msgr><ver>2</ver><client><name>.</name><ver>.</ver><networks>skype</networks></client><epid>" + EPID + "</epid></msgr>");
        break;
      case "BND":
        String challenge = getXMLField(packet.body, "nonce");
        if (challenge != null) {
          logger.severe("Nonce field sent in BND message! Challenge needed but not included in this release: nonce: " + challenge);
          skype.error(new IOException(
              "Skype sent a nonce in the BND request, but it shouldn't do so anymore. If you see this error please open an issue on https://github.com/Delthas/JavaSkype/issues"));
        }
        String formattedPublicationBody = String.format(
            "<user><s n=\"IM\"><Status>%s</Status></s><sep n=\"IM\" epid=\"{%s}\"><Capabilities>0:4194560</Capabilities></sep><s n=\"SKP\"><Mood/><Skypename>%s</Skypename></s><sep n=\"SKP\" epid=\"{%s}\"><Version>.</Version><Seamless>true</Seamless></sep></user>",
            skype.getSelf().getPresence().getPresenceString(), EPID, EPID, username, EPID);
        String formattedPublicationMessage = FormattedMessage.format("8:" + username + ";epid={" + EPID + "}", "8:" + username, "Publication: 1.0",
            formattedPublicationBody, "Uri: /user", "Content-Type: application/user+xml");
        sendPacket("PUT", "MSGR\\PRESENCE", formattedPublicationMessage);
        sendPacket("PUT", "MSGR\\SUBSCRIPTIONS",
            "<subscribe><presence><buddies><all /></buddies></presence><messaging><im /><conversations /></messaging></subscribe>");
        StringBuilder contactsStringBuilder = new StringBuilder("<ml l=\"1\"><skp>");
        if (!skype.getContacts().isEmpty()) {
          for (User contact : skype.getContacts()) {
            if (contactsStringBuilder.length() > 30000) {
              contactsStringBuilder.append("</skp></ml>");
              String contactsString = contactsStringBuilder.toString();
              sendPacket("PUT", "MSGR\\CONTACTS", contactsString);
              contactsStringBuilder.delete(0, contactsStringBuilder.length());
              contactsStringBuilder.append("<ml l=\"1\"><skp>");
            }
            contactsStringBuilder.append("<c n=\"");
            contactsStringBuilder.append(contact.getUsername());
            contactsStringBuilder.append("\" t=\"8\"><s l=\"3\" n=\"IM\"/><s l=\"3\" n=\"SKP\"/></c>");
          }
          contactsStringBuilder.append("</skp></ml>");
          String contactsString = contactsStringBuilder.toString();
          sendPacket("PUT", "MSGR\\CONTACTS", contactsString);
        }
        sendPacket("GET", "MSGR\\RECENTCONVERSATIONS", "<recentconversations><start>0</start><pagesize>100</pagesize></recentconversations>");
        break;
      case "OUT":
        // we got disconnected
        logger.warning("Disconnected from Skype: " + packet.body);
        skype.error(new IOException("Disconnected: " + packet.body));
        break;
      case "PUT":
        break;
      case "PNG":
        if (connectLatch.getCount() > 0L) {
          logger.finest("Received first pong");
          logger.fine("Connected! Stopped blocking.");
          connectLatch.countDown(); // stop blocking: we're connected
        }
        break;
      default:
        System.out.println("Received unknown message: " + packet);
    }

  }

  private Packet readPacket() throws IOException {
    StringBuilder firstLineBuilder = new StringBuilder();
    int read;
    boolean crFlag = false;
    while (true) {
      if ((read = inputStream.read()) == -1) {
        logger.warning("EOF reached in stream");
        return null;
      }
      char character = (char) (read & 0xFF);
      if (crFlag) {
        if (character == '\n') {
          break;
        }
        ParseException e = new ParseException("Received \\r without \\n in: " + firstLineBuilder.toString());
        logger.log(Level.SEVERE, "", e);
        throw e;
      }
      if (character == '\n') {
        break;
      }
      if (character == '\r') {
        crFlag = true;
      } else {
        firstLineBuilder.append(character);
      }
    }
    String firstLine = firstLineBuilder.toString();
    Matcher matcherFirstLine = patternFirstLine.matcher(firstLine);
    if (!matcherFirstLine.matches()) {
      ParseException e = new ParseException("Error matching message first line: " + firstLine);
      logger.log(Level.SEVERE, "", e);
      throw e;
    }
    String command = matcherFirstLine.group(1);
    String parameters = matcherFirstLine.group(2);
    String payloadSizeString = matcherFirstLine.group(3);
    int payloadSize;
    try {
      payloadSize = Integer.parseInt(payloadSizeString);
    } catch (NumberFormatException e) {
      throw new ParseException(e);
    }
    byte[] payloadRaw = new byte[payloadSize];
    int bytesRead = 0;
    while (bytesRead != payloadSize) {
      int n = inputStream.read(payloadRaw, bytesRead, payloadSize - bytesRead);
      if (n == -1) {
        ParseException e = new ParseException("EOF when reading message payload (size: " + payloadSize + ")");
        logger.log(Level.SEVERE, "", e);
        throw e;
      }
      bytesRead += n;
    }

    String payload = new String(payloadRaw, StandardCharsets.UTF_8);

    if (command.matches("\\d+")) {
      ParseException e = new ParseException("Error message received:\n" + firstLine + "\n" + payload);
      logger.log(Level.SEVERE, "", e);
      throw e;
    }

    Matcher matcherHeaders = patternHeaders.matcher(payload);
    if (!matcherHeaders.find()) {
      ParseException e = new ParseException("Couldn't find headers in payload: " + payload);
      logger.log(Level.SEVERE, "", e);
      throw e;
    }
    String newRegistration = matcherHeaders.group(1);
    if (newRegistration != null) {
      logger.finest("Set registration: " + newRegistration);
      registration = newRegistration;
    }
    String body = payload.substring(matcherHeaders.end());
    return new Packet(command, parameters, body);
  }

  public void connect() throws IOException, InterruptedException {
    logger.finer("Starting notification connector");
    lastMessageSentTime = System.nanoTime();
    connectTo(DEFAULT_SERVER_HOSTNAME, DEFAULT_SERVER_PORT);
    new Thread(() -> {
      while (!disconnectRequested) {
        try {
          Packet packet = readPacket();
          if (packet == null) {
            continue;
          }
          processPacket(packet);
        } catch (IOException e) {
          if (disconnectRequested) {
            // there may be errors reading from the closed stream when disconnecting
            // quit without throwing
            return;
          }
          logger.log(Level.SEVERE, "Error while reading packet", e);
          skype.error(e);
          connectLatch.countDown();
          break;
        }
      }
    }, "Skype-Receiver-Thread").start();

    pingThread = new Thread(() -> {
      while (!Thread.interrupted() && !disconnectRequested) {
        if (System.nanoTime() - lastMessageSentTime > pingInterval) {
          try {
            sendPacket("PNG", "CON", "");
            sendPacket("PUT", "MSGR\\ACTIVEENDPOINT", "<activeendpoint><timeout>135</timeout></activeendpoint>");
          } catch (IOException e) {
            logger.log(Level.SEVERE, "Error while sending ping", e);
            skype.error(e);
            break;
          }
        }
        try {
          Thread.sleep(pingInterval / 1000000);
        } catch (InterruptedException e) {
          return;
        }
      }
    }, "Skype-Ping-Thread");
    logger.finest("Ping interval: " + (pingInterval / 1000000) + "ms");

    logger.finer("Waiting for connection");
    connectLatch.await(); // block until connected

    pingThread.start();
  }

  public void sendMessage(Chat chat, Message message) throws IOException {
    String entity = "";
    switch (chat.getType()) {
      case GROUP:
        entity = chat.getIdentity();
        break;
      case USER:
        entity = "8:" + chat.getIdentity();
        break;
    }
    sendMessage(entity, message);
  }

  @Deprecated
  public void sendUserMessage(User user, String message) throws IOException {
    sendMessage("8:" + user.getUsername(), getSanitized(message));
  }

  @Deprecated
  public void sendGroupMessage(Group group, String message) throws IOException {
    sendMessage(group.getId(), getSanitized(message));
  }

  public void addUserToGroup(User user, Role role, Group group) throws IOException {
    String body = String.format("<thread><id>%s</id><members><member><mri>8:%s</mri><role>%s</role></member></members></thread>",
        group.getId(), user.getUsername(), role.getRoleString());
    sendPacket("PUT", "MSGR\\THREAD", body);
  }

  public void removeUserFromGroup(User user, Group group) throws IOException {
    String body = String.format("<thread><id>%s</id><members><member><mri>8:%s</mri></member></members></thread>", group.getId(),
        user.getUsername());
    sendPacket("DEL", "MSGR\\THREAD", body);
  }

  public void changeGroupTopic(Group group, String topic) throws IOException {
    String body =
        String.format("<thread><id>%s</id><properties><topic>%s</topic></properties></thread>", group.getId(), getSanitized(topic));
    sendPacket("PUT", "MSGR\\THREAD", body);
  }

  public void changeUserRole(User user, Role role, Group group) throws IOException {
    String body = String.format("<thread><id>%s</id><members><member><mri>8:%s</mri><role>%s</role></member></members></thread>",
        group.getId(), user.getUsername(), role.getRoleString());
    sendPacket("PUT", "MSGR\\THREAD", body);
  }

  public void changePresence(Presence presence) throws IOException {
    String formattedPublicationBody = String.format("<user><s n=\"IM\"><Status>" + presence.getPresenceString() + "</Status></s></user>");
    String formattedPublicationMessage = FormattedMessage.format("8:" + username + ";epid={" + EPID + "}", "8:" + username, "Publication: 1.0",
        formattedPublicationBody, "Uri: /user", "Content-Type: application/user+xml");
    sendPacket("PUT", "MSGR\\PRESENCE", formattedPublicationMessage);
  }

  @Deprecated
  private void sendMessage(String entity, String message) throws IOException {
    String body = FormattedMessage.format("8:" + username + ";epid={" + EPID + "}", entity, "Messaging: 2.0", message,
        "Content-Type: application/user+xml", "Message-Type: RichText");
    sendPacket("SDG", "MSGR", body);
  }

  private void sendMessage(String entity, Message message) throws IOException {
    List<String> headers = new ArrayList<>(Arrays.asList("Content-Type: application/user+xml", "Message-Type: " + MessageType.getTypeByClass(message).getHeaderType()));
    if (message.getId() != null) {
      headers.add("Skype-EditedId: " + message.getId());
    }
    AbstractMessage abstractMessage = (AbstractMessage) message;
    String body = "";
    switch (message.getType()) {
      case TEXT:
        TextMessage textMessage = (TextMessage) message;
        body = FormattedMessage.format("8:" + username + ";epid={" + EPID + "}", entity, "Messaging: 2.0", getSanitized(textMessage.getHtml()),
            headers.toArray(new String[headers.size()]));
        sendPacket("SDG", "MSGR", body);
        break;
      case PICTURE:
      case FILE:
      case VIDEO:
      case CONTACT:
      case MOJI:
        if (abstractMessage.isEmpty()) {
          body = FormattedMessage.format("8:" + username + ";epid={" + EPID + "}", entity, "Messaging: 2.0", "",
              headers.toArray(new String[headers.size()]));
          sendPacket("SDG", "MSGR", body);
        } else {
          //Send this type of message not implemented yet;
        }
        break;
      case UNKNOWN:
        logger.warning("Strange UNKNOWN message has been tried to send.");
        break;
    }

  }

  public synchronized void disconnect() {
    logger.finer("Stopping notification connector");
    try {
      sendPacket("OUT", "CON", "");
    } catch (IOException e) {
      // we're closing anyway
      logger.log(Level.FINE, "Error received while disconnecting", e);
    }
    disconnectRequested = true;
    pingThread.interrupt();
    connectLatch.countDown();
    if (socket != null) {
      try {
        socket.close();
      } catch (IOException e) {
        // ignore any error during close
        logger.log(Level.WARNING, "Error while trying to close the socket", e);
      }
    }
  }

  private synchronized void sendPacket(String command, String parameters, String body) throws IOException {
    String headerString = registration != null ? "Registration: " + registration + "\r\n" : "";
    String messageString = String.format("%s %d %s %d\r\n%s\r\n%s", command, ++sequenceNumber, parameters,
        body.getBytes(StandardCharsets.UTF_8).length + 2 + headerString.length(), headerString, body);
    try {
      writer.write(messageString);
      writer.flush();
    } catch (IOException e) {
      logger.log(Level.SEVERE, "Error while trying to send message: " + messageString, e);
      throw e;
    }
    logger.finest("Sent packet: " + messageString);
    lastMessageSentTime = System.nanoTime();
  }

  private void connectTo(String hostname, int port) throws IOException {
    logger.finest("Connecting to hostname: " + hostname + " port: " + port);
    if (socket != null) {
      socket.close();
    }
    socket = SSLSocketFactory.getDefault().createSocket(hostname, port);
    writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
    inputStream = new BufferedInputStream(socket.getInputStream());
    sequenceNumber = 0;
    sendPacket("CNT", "CON", "<connect><ver>2</ver><agent><os>.</os><osVer>.</osVer><proc>.</proc><lcid>en-us</lcid></agent></connect>");
  }

  private void updateThread(Node threadNode) {
    Group group = null;
    String topic = null;
    Node members = null;
    for (int i = 0; i < threadNode.getChildNodes().getLength(); i++) {
      Node node = threadNode.getChildNodes().item(i);
      if (node.getNodeName().equals("id")) {
        group = (Group) parseEntity(node.getTextContent());
      } else if (node.getNodeName().equals("members")) {
        members = node;
      } else if (node.getNodeName().equals("properties")) {
        for (int j = 0; j < node.getChildNodes().getLength(); j++) {
          Node topicNode = node.getChildNodes().item(j);
          if (topicNode.getNodeName().equals("topic")) {
            topic = topicNode.getTextContent();
          }
        }
      }
    }
    if (group == null || topic == null || members == null) {
      return;
    }
    List<Pair<User, Role>> users = new ArrayList<>(members.getChildNodes().getLength());
    for (int i = 0; i < members.getChildNodes().getLength(); i++) {
      Node memberNode = members.getChildNodes().item(i);
      if (!memberNode.getNodeName().equals("member")) {
        continue;
      }
      User user = null;
      Role role = null;
      for (int j = 0; j < memberNode.getChildNodes().getLength(); j++) {
        Node memberPropertyNode = memberNode.getChildNodes().item(j);
        if (memberPropertyNode.getNodeName().equals("mri")) {
          user = (User) parseEntity(memberPropertyNode.getTextContent());
          skype.updateUser(user);
        } else if (memberPropertyNode.getNodeName().equals("role")) {
          role = Role.getRole(memberPropertyNode.getTextContent());
        }
      }
      if (user != null && role != null) {
        users.add(new Pair<>(user, role));
      }
    }
    group.setTopic(topic);
    group.setUsers(users);
  }

  private Object parseEntity(String rawEntity) {
    // returns a user or a group
    logger.finest("Parsing entity " + rawEntity);
    int senderBegin = rawEntity.indexOf(':');
    int network = Integer.parseInt(rawEntity.substring(0, senderBegin));
    if (network == 8) {
      int i = rawEntity.indexOf(';');
      return skype.getUser(rawEntity.substring(2, i == -1 ? rawEntity.length() : i));
    } else if (network == 19) {
      return skype.getGroup(rawEntity);
    } else {
      logger.warning("Error while parsing entity " + rawEntity + ": unknown network:" + network);
      throw new IllegalArgumentException();
    }
  }

  private Document getDocument(String XML) throws ParseException {
    try {
      return documentBuilder.parse(new InputSource(new StringReader(XML)));
    } catch (IOException | SAXException e) {
      // IOException should never happen, but treat as ParseException anyway
      logger.log(Level.WARNING, "Error while parsing XML String: " + XML, e);
      throw new ParseException(e);
    }
  }

  private List<String> getXMLFields(String XML, String fieldName) throws ParseException {
    NodeList nodes = getDocument(XML).getElementsByTagName(fieldName);
    List<String> fields = new ArrayList<>(nodes.getLength());
    for (int i = 0; i < nodes.getLength(); i++) {
      fields.add(nodes.item(i).getTextContent());
    }
    return fields;
  }

  private String getXMLField(String XML, String fieldName) throws ParseException {
    List<String> fields = getXMLFields(XML, fieldName);
    if (fields.size() > 1) {
      throw new ParseException();
    }
    if (fields.size() == 0) {
      return null;
    }
    return fields.get(0);
  }

  private static String generateEPID() {
    char[] hexCharacters = {'0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'};
    // EPID format: XXXXXXXX-XXXX-XXXX-XXXX-XXXXXXXXXXXX
    char[] EPIDchars = new char[36];
    EPIDchars[8] = '-';
    EPIDchars[13] = '-';
    EPIDchars[18] = '-';
    EPIDchars[23] = '-';
    Random random = new Random();
    for (int i = 0; i < 8; i++) {
      EPIDchars[i] = hexCharacters[random.nextInt(hexCharacters.length)];
    }
    for (int i = 9; i < 13; i++) {
      EPIDchars[i] = hexCharacters[random.nextInt(hexCharacters.length)];
    }
    for (int i = 14; i < 18; i++) {
      EPIDchars[i] = hexCharacters[random.nextInt(hexCharacters.length)];
    }
    for (int i = 19; i < 23; i++) {
      EPIDchars[i] = hexCharacters[random.nextInt(hexCharacters.length)];
    }
    for (int i = 24; i < 36; i++) {
      EPIDchars[i] = hexCharacters[random.nextInt(hexCharacters.length)];
    }
    String EPID = new String(EPIDchars);
    logger.finest("Generated EPID: " + EPID);
    return EPID;
  }

  private static String getPlaintext(String string) {
    return Jsoup.parseBodyFragment(string).text();
  }

  private static String getSanitized(String raw) {
    if (raw.isEmpty())
      return raw;
    StringBuilder sb = new StringBuilder(raw.length());
    boolean crFlag = false;
    for (int i = 0; i < raw.length(); i++) {
      char c = raw.charAt(i);
      if (c <= 0x1F || (c >= 0x7F && c <= 0x9F)) {
        if (c == '\r') {
          if (crFlag) {
            sb.append('\r').append('\n');
          }
          crFlag = true;
        } else if (c == '\n') {
          sb.append('\r').append('\n');
          crFlag = false;
        } else if (crFlag) {
          sb.append('\r').append('\n');
          crFlag = false;
        }
      } else {
        if (crFlag) {
          sb.append('\r').append('\n');
        }
        crFlag = false;
        sb.append(c);
      }
    }
    if (crFlag) {
      sb.append('\r').append('\n');
    }
    return sb.toString();
  }

}
