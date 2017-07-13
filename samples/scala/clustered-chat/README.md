# Scala clustered chat example

This is a clustered version of the multi room chat server example written with Play socket.io. It demonstrates how to run Play socket.io in a multi node environment. It uses a consistent hashing cluster group router to route sessions, and uses Akka distributed pubsub to publish/subscribe to rooms.

The [`cluster.sh`](./cluster.sh) script has been provided to facilitate with building and then running the app in a cluster, it starts up three nodes, and starts up an nginx load balancer in front of the three nodes. Configuration for the load balancer can be found [here](./nginx.conf). It requires that `nginx` is available on your path.

To start the cluster, run `./cluster.sh start`, and then visit [`http://localhost:9000`](http://localhost:9000). To shut down the cluster, run `./cluster.sh stop`.

Some debug logging has been turned on which demonstrates the cluster in action. Each node adds its name (node1, node2 or node3) to its output logs, so you can see which node received the request, and also which node the socket.io session is running on. You can also see that the socket.io session may be running on multiple nodes, yet users connected to those different nodes can chat with each other.

Here's an example cluster session (some info removed from the logs, such as timestamps, to reduce noise):

```
[node2] p.e.EngineIOController - Received new connection for 81111be9-fb14-4080-a224-a8bbed31b5b2
[node1] application - Starting 81111be9-fb14-4080-a224-a8bbed31b5b2 session
[node3] p.e.EngineIOController - Received poll request for 81111be9-fb14-4080-a224-a8bbed31b5b2
[node2] p.e.EngineIOController - Received WebSocket request for 81111be9-fb14-4080-a224-a8bbed31b5b2
[node1] p.e.EngineIOController - Received push request for 81111be9-fb14-4080-a224-a8bbed31b5b2
[node1] p.e.EngineIOController - Received new connection for bb910f67-aac2-42ff-8539-77ff8eda8292
[node2] application - Starting bb910f67-aac2-42ff-8539-77ff8eda8292 session
[node1] p.e.EngineIOController - Received poll request for bb910f67-aac2-42ff-8539-77ff8eda8292
[node2] p.e.EngineIOController - Received poll request for bb910f67-aac2-42ff-8539-77ff8eda8292
[node3] p.e.EngineIOController - Received WebSocket request for bb910f67-aac2-42ff-8539-77ff8eda8292
[node3] p.e.EngineIOController - Received poll request for bb910f67-aac2-42ff-8539-77ff8eda8292
[node2] p.e.EngineIOController - Received push request for bb910f67-aac2-42ff-8539-77ff8eda8292
```

This shows two socket.io sessions, one identified by `81111be9-fb14-4080-a224-a8bbed31b5b2`, and one identified by `bb910f67-aac2-42ff-8539-77ff8eda8292`. The messages logged by the `application` logger shows which node the session is actually created on, so you can see the first one was started on `node1`, the second on `node2` (this correlation was purely chance, by the hash value of the two session ids). Meanwhile, the actual requests for the sessions, logged by the `EngineIOController` are arriving on a combination of all nodes.