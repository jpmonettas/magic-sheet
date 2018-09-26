# magic-sheet

Make all those Clojure[Script] repeating repl commands one keystroke away.

Connect to any nrepl server (lein repl, boot repl, fighwheel nrepl, etc), define some commands and run them with a keystroke.

Lots of times when developing or dev testing Clojure[Script] applications I find myself at the repl firing a bunch of commands 
only with the purpose of checking or changing the state of the app. For example : querying some db tables, checking the state of the cache,
checking blockchain time or account balances, restaring your components, incrementing time, clearing your cache, etc.

If that workflow sounds familiar, magic-sheet can help you.

It lets you create a sheet in which you arrange your most used repl commands so you have them one keystroke away.

Take a look at the screenshot at the end to see a example.

## Prerequisites 

- Install JDK 8 (if you install OpenJDK make sure you install openjfx)
- Install clojure cli tool (https://clojure.org/guides/getting_started)

## Building 

```bash
javac java-src/utils/*.java

clj -Auberjar # (Optional) you can create a uberjar

```

## Basic usage

### Run it

You can connect magic-sheet to any nrepl server like :

```bash
# if you created a uberjar 
java -jar magic-sheet.jar --port 40338

# if you want to run it directly with clj tool
clj -m magic-sheet.core -port 40338
```

The first time you run it you will just see a empty sheet. 

for more options try  `--help`

### Define a new command

For defining a new command, right click anywhere on the sheet which should show a context menu. Click on `New Command`.
This will open the new command dialog, which looks like:

<img src="/doc/create-command.png?raw=true"/>

Then : 

- Choose a title.
- Add your clojure code. (I recommend trying your commands in your prefered repl first until you are happy with it)
- Add a key (just type any character), so you can use the keyboard instead of the mouse to run the command.
- Choose a result type. 

You can leave result type empty if you are only interested in the side effect, and not in the return value (like clear your cache or inc a counter inside an atom).

Result as table can be used if you know your command result returns a collection of maps that can be visualized as a table.

Result as value will show you the return value pretty printed, using the standard clojure.pprint/pprint.

You can also parametrize commands using `${param-name}` placeholders like:

```clojure
(my-app.server.dev/increment-time ${time})
```

### Designing your sheet

You can drag and resize your command widgets as you like, so you can accomodate related functionality together.

### Running a command

You can run commands using your mouse or your keyboard. Click on the command widget title or hit the assigned key.
You will see it flashing green while it is running.

### Saving and restoring your sheet

Right click on the background and then `Save sheet`. This will save the current sheet inside magic-sheet.edn in your current folder.
Everytime you run magic-sheet it searches for that file inside current folder and initialize with it's content. 

### Other features

- Clicking on any table cell will automatically put the content in your clipboard.
- Clicking on Edit button allows you to change your title, code or assigned key.

### How to use it with ClojureScript and figwheel

Figwheel can start a nrepl server if you tell it so. Check figwheels `:nrepl-port`

```clojure 
(figwheel-sidecar.repl-api/start-figwheel!
   (-> (figwheel-sidecar.config/fetch-config)
       (assoc-in [:data :figwheel-options :nrepl-port]  7778))
    "dev-ui")
```

## How does it looks?

<img src="/doc/sheet-sample.png?raw=true"/>

## Roadmap

- Support multiple connections so you can use your client and server repls at the same time
- Support other kinds of repls
