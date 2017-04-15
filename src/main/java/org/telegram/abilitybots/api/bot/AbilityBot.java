package org.telegram.abilitybots.api.bot;

import org.apache.commons.io.IOUtils;
import org.telegram.abilitybots.api.db.DBContext;
import org.telegram.abilitybots.api.objects.*;
import org.telegram.abilitybots.api.sender.DefaultMessageSender;
import org.telegram.abilitybots.api.sender.MessageSender;
import org.telegram.abilitybots.api.util.AbilityUtils;
import org.telegram.abilitybots.api.util.Pair;
import org.telegram.abilitybots.api.util.Trio;
import org.telegram.telegrambots.api.methods.GetFile;
import org.telegram.telegrambots.api.methods.send.SendDocument;
import org.telegram.telegrambots.api.methods.send.SendMessage;
import org.telegram.telegrambots.api.objects.Message;
import org.telegram.telegrambots.api.objects.Update;
import org.telegram.telegrambots.api.objects.replykeyboard.ForceReplyKeyboard;
import org.telegram.telegrambots.bots.DefaultBotOptions;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.exceptions.TelegramApiException;
import org.telegram.telegrambots.logging.BotLogger;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static com.google.common.base.Strings.isNullOrEmpty;
import static java.lang.String.format;
import static java.time.ZonedDateTime.now;
import static java.util.Arrays.stream;
import static java.util.Objects.nonNull;
import static java.util.Optional.ofNullable;
import static java.util.function.Function.identity;
import static java.util.regex.Pattern.CASE_INSENSITIVE;
import static java.util.regex.Pattern.compile;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;
import static jersey.repackaged.com.google.common.base.Throwables.propagate;
import static org.telegram.abilitybots.api.db.MapDBContext.onlineInstance;
import static org.telegram.abilitybots.api.objects.Ability.builder;
import static org.telegram.abilitybots.api.objects.EndUser.fromUser;
import static org.telegram.abilitybots.api.objects.Flag.*;
import static org.telegram.abilitybots.api.objects.Locality.*;
import static org.telegram.abilitybots.api.objects.Privacy.*;
import static org.telegram.abilitybots.api.util.AbilityUtils.commitTo;
import static org.telegram.abilitybots.api.util.AbilityUtils.isUserMessage;

/**
 * The <b>father</b> of all ability bots. Bots that need to utilize abilities need to extend this bot.
 * <p>
 * It's important to note that this bot strictly extends {@link TelegramLongPollingBot}.
 * <p>
 * All bots extending the {@link AbilityBot} get implicit abilities:
 * <ul>
 * <li>/claim - Claims this bot</li>
 * <ul>
 * <li>Sets the user as the {@link Privacy#CREATOR} of the bot</li>
 * <li>Only the user with the ID returned by {@link AbilityBot#creatorId()} can genuinely claim the bot</li>
 * </ul>
 * <li>/commands - reports all user-defined commands (abilities)</li>
 * <li>/backup - returns a backup of the bot database</li>
 * <li>/recover - recovers the database</li>
 * <li>/promote <code>@username</code> - promotes user to bot admin</li>
 * <li>/demote <code>@username</code> - demotes bot admin to user</li>
 * <ul>
 * <li>The same format acceptable by BotFather</li>
 * </ul>
 * <li>/ban <code>@username</code> - bans the user from accessing your bot commands and features</li>
 * <li>/unban <code>@username</code> - lifts the ban from the user</li>
 * </ul>
 * <p>
 * Additional information of the implicit abilities are present in the methods that declare them.
 * <p>
 * The two most important handles in the AbilityBot are the {@link DBContext} <b><code>db</code></b> and the {@link MessageSender} <b><code>sender</code></b>.
 * All bots extending AbilityBot can use both handles in their update consumers.
 *
 * @author Abbas Abou Daya
 */
public abstract class AbilityBot extends TelegramLongPollingBot {
  private static final String TAG = AbilityBot.class.getSimpleName();

  // DB objects
  public static final String ADMINS = "ADMINS";
  public static final String USERS = "USERS";
  public static final String BLACKLIST = "BLACKLIST";

  // Factory commands
  public static final String DEFAULT = "default";
  public static final String CLAIM = "claim";
  public static final String BAN = "ban";
  public static final String PROMOTE = "promote";
  public static final String DEMOTE = "demote";
  public static final String UNBAN = "unban";
  public static final String BACKUP = "backup";
  public static final String RECOVER = "recover";
  public static final String COMMANDS = "commands";

  // Messages
  public static final String RECOVERY_MESSAGE = "I am ready to receive the backup file. Please reply to this message with the backup file attached.";
  public static final String RECOVER_SUCCESS = "I have successfully recovered.";

  // DB and sender
  protected final DBContext db;
  protected MessageSender sender;

  // Bot token and username
  private final String botToken;
  private final String botUsername;

  // Ability registry
  private Map<String, Ability> abilities;
  // Reply registry
  private List<Reply> replies;

  protected AbilityBot(String botToken, String botUsername, DBContext db, DefaultBotOptions botOptions) {
    super(botOptions);

    this.botToken = botToken;
    this.botUsername = botUsername;
    this.db = db;
    this.sender = new DefaultMessageSender(this);

    registerAbilities();
  }

  protected AbilityBot(String botToken, String botUsername, DBContext db) {
    this(botToken, botUsername, db, new DefaultBotOptions());
  }

  protected AbilityBot(String botToken, String botUsername, DefaultBotOptions botOptions) {
    this(botToken, botUsername, onlineInstance(botUsername), botOptions);
  }

  protected AbilityBot(String botToken, String botUsername) {
    this(botToken, botUsername, onlineInstance(botUsername));
  }

  public static Predicate<Update> isReplyTo(String msg) {
    return update -> update.getMessage().getReplyToMessage().getText().equals(msg);
  }

  public abstract int creatorId();

  /**
   * This method contains the stream of actions that are applied on any update.
   * <p>
   * It will correctly handle addition of users into the DB and the execution of abilities and replies.
   *
   * @param update the update received by Telegram's API
   */
  @Override
  public void onUpdateReceived(Update update) {
    BotLogger.info(format("New update [%s] received at %s", update.getUpdateId(), now()), TAG);
    BotLogger.info(update.toString(), TAG);
    long millisStarted = System.currentTimeMillis();

    Stream.of(update)
        .filter(this::checkGlobalFlags)
        .filter(this::checkBlacklist)
        .map(this::addUser)
        .filter(this::filterReply)
        .map(this::getAbility)
        .filter(this::validateAbility)
        .filter(this::checkMessageFlags)
        .filter(this::checkPrivacy)
        .filter(this::checkLocality)
        .filter(this::checkInput)
        .map(this::getContext)
        .map(this::consumeUpdate)
        .forEach(this::postConsumption);

    long processingTime = System.currentTimeMillis() - millisStarted;
    BotLogger.info(format("Processing of update [%s] ended at %s%n---> Processing time: [%d ms] <---%n", update.getUpdateId(), now(), processingTime), TAG);
  }

  @Override
  public String getBotToken() {
    return botToken;
  }

  @Override
  public String getBotUsername() {
    return botUsername;
  }

  /**
   * Test the update against the provided global flags. The default implementation requires a {@link Flag#MESSAGE}.
   * <p>
   * This method should be <b>overridden</b> if the user wants updates that don't require a MESSAGE to pass through.
   *
   * @param update a Telegram {@link Update}
   * @return <tt>true</tt> if the update satisfies the global flags
   */
  protected boolean checkGlobalFlags(Update update) {
    return MESSAGE.test(update);
  }

  /**
   * Gets the user with the specified username.
   *
   * @param username the username of the required user
   * @return an optional describing the user
   */
  protected Optional<EndUser> getUser(String username) {
    return db.<EndUser>getSet(USERS).stream().filter(user -> user.username().equalsIgnoreCase(username)).findFirst();
  }

  /**
   * Gets the user with the specified ID.
   *
   * @param id the id of the required user
   * @return an optional describing the user
   */
  protected Optional<EndUser> getUser(int id) {
    return db.<EndUser>getSet(USERS).stream().filter(user -> user.id() == id).findFirst();
  }

  /**
   * <p>
   * Format of the report:
   * <p>
   * [command1] - [description1]
   * <p>
   * [command2] - [description2]
   * <p>
   * ...
   * <p>
   * Once you invoke it, the bot will send the available commands to the chat. This is a public ability so anyone can invoke it.
   * <p>
   * Usage: <code>/commands</code>
   *
   * @return the ability to report commands defined by the child bot.
   */
  public Ability reportCommands() {
    return builder()
        .name(COMMANDS)
        .locality(ALL)
        .privacy(PUBLIC)
        .input(0)
        .action(ctx -> {
          String commands = abilities.entrySet().stream()
              .filter(entry -> nonNull(entry.getValue().info()))
              .map(entry -> {
                String name = entry.getValue().name();
                String info = entry.getValue().info();
                return format("%s - %s", name, info);
              })
              .sorted()
              .reduce((a, b) -> format("%s%n%s", a, b))
              .orElse("No public commands found.");

          sender.send(commands, ctx.chatId());
        })
        .build();
  }

  /**
   * This backup ability returns the object defined by {@link DBContext#backup()} as a message document.
   * <p>
   * This is a high-profile ability and is restricted to the CREATOR only.
   * <p>
   * Usage: <code>/backup</code>
   *
   * @return the ability to back-up the database of the bot
   */
  public Ability backupDB() {
    return builder()
        .name(BACKUP)
        .locality(USER)
        .privacy(CREATOR)
        .input(0)
        .action(ctx -> {
          File backup = new File("backup.json");

          try (PrintStream printStream = new PrintStream(backup)) {
            printStream.print(db.backup());
            sender.sendDocument(new SendDocument()
                .setNewDocument(backup)
                .setChatId(ctx.chatId())
            );
          } catch (FileNotFoundException e) {
            BotLogger.error("Error while fetching backup", TAG, e);
          } catch (TelegramApiException e) {
            BotLogger.error("Error while sending document/backup file", TAG, e);
          }
        })
        .build();
  }

  /**
   * Recovers the bot database using {@link DBContext#recover(Object)}.
   * <p>
   * The bot recovery process hugely depends on the implementation of the recovery method of {@link DBContext}.
   * <p>
   * Usage: <code>/recover</code>
   *
   * @return the ability to recover the database of the bot
   */
  public Ability recoverDB() {
    return builder()
        .name(RECOVER)
        .locality(USER)
        .privacy(CREATOR)
        .input(0)
        .action(ctx -> {
          SendMessage message = new SendMessage();
          message.setChatId(ctx.chatId());
          message.setText(RECOVERY_MESSAGE);
          message.setReplyMarkup(new ForceReplyKeyboard());

          try {
            sender.sendMessage(message);
          } catch (TelegramApiException e) {
            e.printStackTrace();
          }
        })
        .reply(update -> {
          Long chatId = update.getMessage().getChatId();
          String fileId = update.getMessage().getDocument().getFileId();

          try (FileReader reader = new FileReader(downloadFileWithId(fileId))) {
            String backupData = IOUtils.toString(reader);
            if (db.recover(backupData)) {
              sender.send(RECOVER_SUCCESS, chatId);
            } else {
              sender.send("Oops, something went wrong during recovery.", chatId);
            }
          } catch (Exception e) {
            BotLogger.error("Could not recover DB from backup", TAG, e);
            sender.send("I have failed to recover.", chatId);
          }
        }, MESSAGE, DOCUMENT, REPLY, isReplyTo(RECOVERY_MESSAGE))
        .build();
  }

  /**
   * Banned users are accumulated in the blacklist. Use {@link DBContext#getSet(String)} with name specified by {@link AbilityBot#BLACKLIST}.
   * <p>
   * Usage: <code>/ban @username</code>
   * <p>
   * <u>Note that admins who try to ban the creator, get banned.</u>
   *
   * @return the ability to ban the user from any kind of <b>bot interaction</b>
   */
  public Ability banUser() {
    return builder()
        .name(BAN)
        .locality(ALL)
        .privacy(ADMIN)
        .input(1)
        .action(ctx -> {
          String username = AbilityUtils.stripTag(ctx.firstArg());
          Optional<Integer> endUser = getUser(username).map(EndUser::id);

          if (checkUser(endUser, ctx.chatId())) {
            Integer userId = endUser.get();
            String bannedUser;

            // Protection from abuse
            if (userId == creatorId()) {
              userId = ctx.user().id();
              bannedUser = isNullOrEmpty(ctx.user().username()) ? AbilityUtils.addTag(ctx.user().username()) : ctx.user().shortName();
            } else {
              bannedUser = AbilityUtils.addTag(username);
            }

            Set<Integer> blacklist = db.getSet(BLACKLIST);
            if (blacklist.add(userId))
              sender.sendMd(format("%s is now *banned*.", bannedUser), ctx.chatId());
            else {
              sender.sendMd(format("%s is already *banned*.", bannedUser), ctx.chatId());
            }
          }
        })
        .post(commitTo(db))
        .build();
  }

  /**
   * Usage: <code>/unban @username</code>
   *
   * @return the ability to unban a user
   */
  public Ability unbanUser() {
    return builder()
        .name(UNBAN)
        .locality(ALL)
        .privacy(ADMIN)
        .input(1)
        .action(ctx -> {
          String username = AbilityUtils.stripTag(ctx.firstArg());
          Optional<Integer> endUser = getUser(username).map(EndUser::id);

          if (checkUser(endUser, ctx.chatId())) {
            Set<Integer> blacklist = db.getSet(BLACKLIST);

            if (!blacklist.remove(endUser.get()))
              sender.sendMd(format("@%s is *not* on the *blacklist*.", username), ctx.chatId());
            else {
              sender.sendMd(format("@%s, your ban has been *lifted*.", username), ctx.chatId());
            }
          }
        })
        .post(commitTo(db))
        .build();
  }

  /**
   * @return the ability to promote a user to a bot admin
   */
  public Ability promoteAdmin() {
    return builder()
        .name(PROMOTE)
        .locality(ALL)
        .privacy(ADMIN)
        .input(1)
        .action(ctx -> {
          String username = AbilityUtils.stripTag(ctx.firstArg());
          Optional<Integer> endUserId = getUser(username).map(EndUser::id);

          if (checkUser(endUserId, ctx.chatId())) {
            Set<Integer> admins = db.getSet(ADMINS);
            if (admins.add(endUserId.get()))
              sender.sendMd(format("@%s has been *promoted*.", username), ctx.chatId());
            else {
              sender.sendMd(format("@%s is already an *admin*.", username), ctx.chatId());
            }
          }
        }).post(commitTo(db))
        .build();
  }

  /**
   * @return the ability to demote an admin to a user
   */
  public Ability demoteAdmin() {
    return builder()
        .name(DEMOTE)
        .locality(ALL)
        .privacy(ADMIN)
        .input(1)
        .action(ctx -> {
          String username = AbilityUtils.stripTag(ctx.firstArg());
          Optional<Integer> endUserId = getUser(username).map(EndUser::id);

          if (checkUser(endUserId, ctx.chatId())) {
            Set<Integer> admins = db.getSet(ADMINS);
            if (admins.remove(endUserId.get())) {
              sender.sendMd(format("@%s has been *demoted*.", username), ctx.chatId());
            } else {
              sender.sendMd(format("@%s is *not* an *admin*.", username), ctx.chatId());
            }
          }
        })
        .post(commitTo(db))
        .build();
  }

  /**
   * Regular users and admins who try to claim the bot will get <b>banned</b>.
   *
   * @return the ability to claim yourself as the master and creator of the bot
   */
  public Ability claimCreator() {
    return builder()
        .name(CLAIM)
        .locality(ALL)
        .privacy(PUBLIC)
        .input(0)
        .action(ctx -> {
          if (ctx.user().id() == creatorId()) {
            Set<Integer> admins = db.getSet(ADMINS);
            int id = creatorId();
            long chatId = ctx.chatId();

            if (admins.add(id))
              sender.send("You're now my master.", chatId);
            else {
              sender.send("You're already my master.", chatId);
            }
          } else {
            // This is not a joke
            abilities.get(BAN).consumer().accept(new MessageContext(ctx.update(), ctx.user(), ctx.chatId(), ctx.user().username()));
          }
        })
        .post(commitTo(db))
        .build();
  }

  /**
   * Registers the declared abilities using method reflection. Also, replies are accumulated using the built abilities.
   * <p>
   * <b>Only abilities with the <u>public</u> accessor are registered!</b>
   */
  private void registerAbilities() {
    try {
      abilities = stream(this.getClass().getMethods())
          .filter(method -> method.getReturnType().equals(Ability.class))
          .map(this::invokeMethod)
          .collect(toMap(Ability::name, identity()));

      replies = abilities.values().stream()
          .flatMap(ability -> ability.replies().stream())
          .collect(toList());

    } catch (IllegalStateException e) {
      BotLogger.error(TAG, "Duplicate names found while registering abilities. Make sure that the abilities declared don't clash with the reserved ones.", e);
      throw propagate(e);
    }

  }

  /**
   * Invoke the method and retrieve its return Ability.
   *
   * @param method a method that returns an ability
   * @return the ability returned by the method
   */
  private Ability invokeMethod(Method method) {
    try {
      return (Ability) method.invoke(this);
    } catch (IllegalAccessException | InvocationTargetException e) {
      BotLogger.error("Could not add ability", TAG, e);
      throw propagate(e);
    }
  }

  private boolean checkUser(Optional<Integer> endUserId, Long chatId) {
    if (!endUserId.isPresent()) {
      sender.send("Sorry, I could not find the specified username.", chatId);
      return false;
    }

    return true;
  }

  private void postConsumption(Pair<MessageContext, Ability> pair) {
    ofNullable(pair.b().postConsumer())
        .ifPresent(consumer -> consumer.accept(pair.a()));
  }

  Pair<MessageContext, Ability> consumeUpdate(Pair<MessageContext, Ability> pair) {
    pair.b().consumer().accept(pair.a());
    return pair;
  }

  Pair<MessageContext, Ability> getContext(Trio<Update, Ability, String[]> trio) {
    Update update = trio.a();
    EndUser user = fromUser(AbilityUtils.getUser(update));

    return Pair.of(new MessageContext(update, user, AbilityUtils.getChatId(update), trio.c()), trio.b());
  }

  boolean checkBlacklist(Update update) {
    Integer id = AbilityUtils.getUser(update).getId();

    return id == creatorId() || !db.<Integer>getSet(BLACKLIST).contains(id);
  }

  boolean checkInput(Trio<Update, Ability, String[]> trio) {
    String[] tokens = trio.c();
    int abilityTokens = trio.b().tokens();

    return abilityTokens == 0 || (tokens.length > 0 && tokens.length == abilityTokens);
  }

  boolean checkLocality(Trio<Update, Ability, String[]> trio) {
    Update update = trio.a();
    Locality locality = isUserMessage(update) ? USER : GROUP;
    Locality abilityLocality = trio.b().locality();
    return abilityLocality == ALL || locality == abilityLocality;
  }

  boolean checkPrivacy(Trio<Update, Ability, String[]> trio) {
    Update update = trio.a();
    EndUser user = fromUser(AbilityUtils.getUser(update));
    Privacy privacy;
    int id = user.id();

    privacy = isCreator(id) ? CREATOR : isAdmin(id) ? ADMIN : PUBLIC;

    return privacy.compareTo(trio.b().privacy()) >= 0;
  }

  private boolean isCreator(int id) {
    return id == creatorId();
  }

  private boolean isAdmin(Integer id) {
    return db.<Integer>getSet(ADMINS).contains(id);
  }

  boolean validateAbility(Trio<Update, Ability, String[]> trio) {
    return trio.b() != null;
  }

  Trio<Update, Ability, String[]> getAbility(Update update) {
    // Handle updates without messages
    // Passing through this function means that the global flags have passed
    Message msg = update.getMessage();
    if (!update.hasMessage() || !msg.hasText())
      return Trio.of(update, abilities.get(DEFAULT), new String[]{});

    // Priority goes to text before captions
    String[] tokens = msg.getText().split(" ");

    if (tokens[0].startsWith("/")) {
      String abilityToken = stripBotUsername(tokens[0].substring(1));
      Ability ability = abilities.get(abilityToken);
      tokens = Arrays.copyOfRange(tokens, 1, tokens.length);
      return Trio.of(update, ability, tokens);
    } else {
      Ability ability = abilities.get(DEFAULT);
      return Trio.of(update, ability, tokens);
    }
  }

  private String stripBotUsername(String token) {
    return compile(format("@%s", botUsername), CASE_INSENSITIVE)
        .matcher(token)
        .replaceAll("");
  }

  Update addUser(Update update) {
    EndUser endUser = fromUser(AbilityUtils.getUser(update));
    Set<EndUser> set = db.getSet(USERS);

    Optional<EndUser> optUser = set.stream().filter(user -> user.id() == endUser.id()).findAny();
    if (!optUser.isPresent()) {
      set.add(endUser);
      db.commit();
      return update;
    } else if (!optUser.get().equals(endUser)) {
      set.remove(optUser.get());
      set.add(endUser);
      db.commit();
    }

    return update;
  }

  boolean filterReply(Update update) {
    return replies.stream()
        .filter(reply -> reply.isOkFor(update))
        .map(reply -> {
          reply.actOn(update);
          return false;
        })
        .reduce(true, Boolean::logicalAnd);
  }

  boolean checkMessageFlags(Trio<Update, Ability, String[]> trio) {
    Ability ability = trio.b();
    Update update = trio.a();

    return ability.flags().stream()
        .map(flag -> flag.test(update))
        .reduce(true, Boolean::logicalAnd);
  }

  private File downloadFileWithId(String fileId) throws TelegramApiException {
    return sender.downloadFile(sender.getFile(new GetFile().setFileId(fileId)));
  }
}