package net.darkness;

import io.netty.buffer.Unpooled;
import net.darkness.Database.UserData;
import reactor.core.publisher.Flux;
import telegram4j.core.MTProtoTelegramClient;
import telegram4j.core.event.domain.inline.CallbackQueryEvent;
import telegram4j.core.event.domain.message.SendMessageEvent;
import telegram4j.core.object.User;
import telegram4j.core.spec.SendMessageSpec;
import telegram4j.core.spec.markup.*;
import telegram4j.core.util.Id;

import java.util.*;
import java.util.stream.Collectors;

public class Main {
    public static final Set<Long> OWNER_IDS = Set.of(
            1132709722L, // Даркнесс - разработчикы
            411873190L,  // Ramesses XIII
            99264970L,   // Tony B
            5089011792L  // Евгений Иванов
    );

    private static final String API_ID = System.getenv("API_ID");
    private static final String API_HASH = System.getenv("API_HASH");
    private static final String BOT_TOKEN = System.getenv("BOT_TOKEN");
    private static final String BOT_LINK = System.getenv("BOT_LINK");

    private static final String WELCOME_MESSAGE = """
            Это приветственное сообщение еще не готово!
            Возможно, оно появится позже 👀

            Кстати, заявка на связь с менеджером уже отправлена, жди ответа!
            """;

    private static final String WELCOME_MANAGER_MESSAGE = """
            Это приветственное сообщение для менеджеров. Если ты его видишь... Ну, ты, собственно, менеджер. Надеюсь.
            """;

    private static final String NEW_REQUEST_MESSAGE = """
            Получена новая заявка от пользователя {user}!
            """;

    private static final String REQUEST_TAKEN_MANAGER_MESSAGE = """
            Принята заявка от {user}!
            ⚠️ Все сообщения из этого чата будут пересланы этому пользователю! ⚠️

            Чтобы прекратить общение, напишите /stop
            """;

    private static final String REQUEST_TAKEN_USER_MESSAGE = """
            Вашу заявку принял менеджер {manager}!
            ⚠️ Все сообщения из этого чата будут пересланы этому менеджеру! ⚠️

            Чтобы прекратить общение, напишите /stop
            """;

    private static final String DIALOG_STOPPED_YOU = """
            ⚠️ Вы завершили диалог с {user}! ⚠️
            """;

    private static final String DIALOG_STOPPED_OTHER = """
            ⚠️ {user} завершил диалог! ⚠️
            """;


    private static final String USER_ALREADY_HAS_DIALOG_MESSAGE = """
            Заявку этого пользователя уже принял кто-то из менеджеров!
            """;

    private static final String YOU_ALREADY_HAVE_DIALOG_MESSAGE = """
            У вас уже есть диалог с одним из пользователей! Используйте /stop, чтобы прекратить его.
            """;

    private static final String INVALID_ID_MESSAGE = """
            ⚠️ ID менеджера должно быть целым числом!
            """;

    private static final String USER_NOT_FOUND_MESSAGE = """
            ⚠️ Пользователь с таким ID не найден!
            """;

    private static final String USER_NOW_MANAGER_MESSAGE = """
            Пользователь {user} теперь является менеджером!
            """;

    private static final String YOU_NOW_MANAGER_MESSAGE = """
            Вы теперь являетесь менеджером и можете принимать заявки!
            """;

    private static final String USER_NOW_NOT_MANAGER_MESSAGE = """
            Пользователь {user} больше не является менеджером!
            """;

    private static final String YOU_NOW_NOT_MANAGER_MESSAGE = """
            Вы больше не являетесь менеджером!
            """;

    private static final String USER_NOT_WAITING_MESSAGE = """
            Этот пользователь больше не ждет ответа ни на какую заявку!
            """;

    private static final String MARK_IS_EMPTY_MESSAGE = """
            UTM-метка пустая!
            """;

    public static MTProtoTelegramClient CLIENT;


    public static void main(String... args) {
        Database.load();
        CLIENT = MTProtoTelegramClient.create(Integer.parseInt(API_ID), API_HASH, BOT_TOKEN)
                .connect()
                .blockOptional()
                .orElseThrow();

        CLIENT.on(SendMessageEvent.class)
                .filter(event -> event.getMessage().getContent().startsWith("/start"))
                .subscribe(event -> {
                    // UTM метка
                    var utmMark = event.getMessage().getContent().substring("/start".length()).trim();

                    var chat = event.getChat().orElseThrow();
                    var author = event.getAuthor().orElseThrow();

                    CLIENT.getUserById(author.getId().withAccessHash(0L)).subscribe(user -> {
                        createUserData(user, utmMark);

                        var data = Database.getUserData(user.getId().asLong());
                        if (data.isManager) {
                            chat.sendMessage(WELCOME_MANAGER_MESSAGE).subscribe();
                            return;
                        } else chat.sendMessage(WELCOME_MESSAGE).subscribe();

                        if (data.hasWaitingRequest) return;

                        Flux.fromIterable(Database.getManagers())
                                .flatMap(manager -> CLIENT.getChatById(Id.ofUser(manager.id).withAccessHash(0L)))
                                .flatMap(managerChat -> managerChat.sendMessage(SendMessageSpec.builder()
                                        .message(NEW_REQUEST_MESSAGE.replace("{user}", data.username))
                                        .replyMarkup(ReplyMarkupSpec.inlineKeyboard(List.of(List.of(InlineButtonSpec.callback("Ответить", true, Unpooled.copyLong(user.getId().asLong()))))))
                                        .build()))
                                .subscribe();

                        data.hasWaitingRequest = true;
                        Database.saveUserData(data);
                    }, Throwable::printStackTrace);
                }, Throwable::printStackTrace);


        CLIENT.on(SendMessageEvent.class)
                .filter(event -> event.getMessage().getContent().startsWith("/stop"))
                .subscribe(event -> {
                    var chat = event.getChat().orElseThrow();
                    var user = event.getAuthor().orElseThrow();

                    var data = Database.getUserData(user.getId().asLong());
                    if (data == null || data.contactingWithID == -1) return;

                    var otherData = Database.getUserData(data.contactingWithID);

                    data.contactingWithID = -1;
                    otherData.contactingWithID = -1;

                    data.hasWaitingRequest = false;
                    otherData.hasWaitingRequest = false;

                    Database.saveUserData(data);
                    Database.saveUserData(otherData);

                    chat.sendMessage(DIALOG_STOPPED_YOU.replace("{user}", otherData.username)).subscribe();

                    CLIENT.getUserById(Id.ofUser(otherData.id).withAccessHash(0L))
                            .map(User::asPrivateChat)
                            .flatMap(userChat -> userChat.sendMessage(DIALOG_STOPPED_OTHER.replace("{user}", data.username)))
                            .subscribe();
                }, Throwable::printStackTrace);


        CLIENT.on(CallbackQueryEvent.class).subscribe(event -> {
            long userID = event.getData().orElseThrow().readLong();
            var chat = event.getChat();

            var userData = Database.getUserData(userID);
            if (userData == null) return;

            var managerData = Database.getUserData(event.getUser().getId().asLong());
            if (managerData == null || !managerData.isManager) return;

            if (userData.isManager || !userData.hasWaitingRequest) {
                chat.sendMessage(USER_NOT_WAITING_MESSAGE).subscribe();
                return;
            }

            if (userData.contactingWithID != -1) {
                chat.sendMessage(USER_ALREADY_HAS_DIALOG_MESSAGE).subscribe();
                return;
            }

            if (managerData.contactingWithID != -1) {
                chat.sendMessage(YOU_ALREADY_HAVE_DIALOG_MESSAGE).subscribe();
                return;
            }

            userData.contactingWithID = managerData.id;
            managerData.contactingWithID = userData.id;

            Database.saveUserData(userData);
            Database.saveUserData(managerData);

            chat.sendMessage(REQUEST_TAKEN_MANAGER_MESSAGE.replace("{user}", userData.username)).subscribe();

            // удаляем изначальное сообщение
            CLIENT.deleteMessages(true, Collections.singletonList(event.getMessageId())).subscribe();
            CLIENT.getUserById(Id.ofUser(userData.id).withAccessHash(0L))
                    .map(User::asPrivateChat)
                    .flatMap(userChat -> userChat.sendMessage(REQUEST_TAKEN_USER_MESSAGE.replace("{manager}", managerData.username)))
                    .subscribe();
        });

        CLIENT.on(SendMessageEvent.class)
                .filter(event -> !event.getMessage().getContent().startsWith("/"))
                .subscribe(event -> {
                    if (event.getMessage().getAuthorId()
                            .map(id -> id.equals(event.getClient().getSelfId()))
                            .orElse(false)) return;

                    var user = event.getAuthor().orElseThrow();
                    var data = Database.getUserData(user.getId().asLong());

                    if (data == null || data.contactingWithID == -1) return;

                    CLIENT.getUserById(Id.ofUser(data.contactingWithID).withAccessHash(0L))
                            .map(User::asPrivateChat)
                            .flatMap(chat -> chat.sendMessage(event.getMessage().getContent())).subscribe();
                });


        CLIENT.on(SendMessageEvent.class)
                .filter(event -> event.getMessage().getContent().startsWith("/addmanager "))
                .subscribe(event -> {
                    if (event.getMessage().getAuthorId()
                            .map(id -> !OWNER_IDS.contains(id.asLong()))
                            .orElse(true)) return;

                    var chat = event.getChat().orElseThrow();
                    long id;

                    try {
                        id = Long.parseLong(event.getMessage().getContent().substring("/addmanager ".length()));
                    } catch (Exception e) {
                        chat.sendMessage(INVALID_ID_MESSAGE).subscribe();
                        return;
                    }

                    var data = Database.getUserData(id);
                    if (data == null) {
                        chat.sendMessage(USER_NOT_FOUND_MESSAGE).subscribe();
                        return;
                    }

                    data.isManager = true;
                    Database.saveUserData(data);

                    chat.sendMessage(USER_NOW_MANAGER_MESSAGE.replace("{user}", data.username)).subscribe();

                    CLIENT.getUserById(Id.ofUser(data.id).withAccessHash(0L))
                            .map(User::asPrivateChat)
                            .flatMap(userChat -> userChat.sendMessage(YOU_NOW_MANAGER_MESSAGE)).subscribe();
                });


        CLIENT.on(SendMessageEvent.class)
                .filter(event -> event.getMessage().getContent().startsWith("/removemanager "))
                .subscribe(event -> {
                    if (event.getMessage().getAuthorId()
                            .map(id -> !OWNER_IDS.contains(id.asLong()))
                            .orElse(true)) return;

                    var chat = event.getChat().orElseThrow();
                    long id;

                    try {
                        id = Long.parseLong(event.getMessage().getContent().substring("/removemanager ".length()));
                    } catch (Exception e) {
                        chat.sendMessage(INVALID_ID_MESSAGE).subscribe();
                        return;
                    }

                    var data = Database.getUserData(id);
                    if (data == null) {
                        chat.sendMessage(USER_NOT_FOUND_MESSAGE).subscribe();
                        return;
                    }

                    data.isManager = false;
                    Database.saveUserData(data);

                    chat.sendMessage(USER_NOW_NOT_MANAGER_MESSAGE.replace("{user}", data.username)).subscribe();

                    CLIENT.getUserById(Id.ofUser(data.id).withAccessHash(0L))
                            .map(User::asPrivateChat)
                            .flatMap(userChat -> userChat.sendMessage(YOU_NOW_NOT_MANAGER_MESSAGE)).subscribe();
                });


        CLIENT.on(SendMessageEvent.class)
                .filter(event -> event.getMessage().getContent().startsWith("/createlink "))
                .subscribe(event -> {
                    if (event.getMessage().getAuthorId()
                            .map(id -> !OWNER_IDS.contains(id.asLong()))
                            .orElse(true)) return;

                    var chat = event.getChat().orElseThrow();

                    var utmMark = event.getMessage().getContent().substring("/createlink ".length()).trim();
                    if (utmMark.isEmpty()) {
                        chat.sendMessage(MARK_IS_EMPTY_MESSAGE).subscribe();
                        return;
                    }

                    chat.sendMessage(BOT_LINK + "?" + utmMark).subscribe();
                });


        CLIENT.on(SendMessageEvent.class)
                .filter(event -> event.getMessage().getContent().startsWith("/managers"))
                .subscribe(event -> {
                    if (event.getMessage().getAuthorId()
                            .map(id -> !OWNER_IDS.contains(id.asLong()))
                            .orElse(true)) return;

                    var chat = event.getChat().orElseThrow();
                    var managers = Database.getManagers();

                    chat.sendMessage(managers.stream()
                            .map(manager -> manager.username + " / ID: " + manager.id + (OWNER_IDS.contains(manager.id) ? " / OWNER" : ""))
                            .collect(Collectors.joining("\n"))).subscribe();
                });


        System.out.println("Connected to Telegram as @" + CLIENT.getUserById(CLIENT.getSelfId())
                .blockOptional()
                .orElseThrow()
                .getUsername()
                .orElseThrow());

        CLIENT.onDisconnect().block();
    }

    private static void createUserData(User user, String utmMark) {
        if (Database.userDataExists(user.getId().asLong())) return;

        Database.saveUserData(new UserData(user.getId().asLong(), utmMark, user.getUsername()
                .map(username -> "@" + username)
                .orElse(user.getFullName())));
    }
}