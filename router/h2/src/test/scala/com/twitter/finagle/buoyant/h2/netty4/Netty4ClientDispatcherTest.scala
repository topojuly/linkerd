package com.twitter.finagle.buoyant.h2
package netty4

import com.twitter.concurrent.AsyncQueue
import com.twitter.finagle.netty4.BufAsByteBuf
import com.twitter.finagle.stats.InMemoryStatsReceiver
import com.twitter.finagle.transport.Transport
import com.twitter.io.Buf
import com.twitter.logging.Level
import com.twitter.util.{Future, Promise, Time}
import io.buoyant.test.FunSuite
import io.netty.handler.codec.http2._
import scala.collection.immutable.Queue

class Netty4ClientDispatchTest extends FunSuite {
  override def logLevel = Level.DEBUG

  test("dispatches multiple concurrent requests on underlying transport") {
    val recvq, sentq = new AsyncQueue[Http2Frame]
    val closeP = new Promise[Throwable]
    val transport = new Transport[Http2Frame, Http2Frame] {
      def status = ???
      def localAddress = ???
      def remoteAddress = ???
      def peerCertificate = ???
      def read(): Future[Http2Frame] = recvq.poll()
      def write(f: Http2Frame): Future[Unit] = {
        sentq.offer(f)
        Future.Unit
      }
      def onClose = closeP
      def close(d: Time): Future[Unit] = {
        closeP.setValue(new Exception)
        Future.Unit
      }
    }

    val stats = new InMemoryStatsReceiver
    val dispatcher = new Netty4ClientDispatcher(transport, Int.MaxValue, stats)

    var released = 0
    def releaser: Int => Future[Unit] = { bytes =>
      released += bytes
      Future.Unit
    }

    // Issue req0
    val req0q = new AsyncQueue[Frame]
    val req0EndP = new Promise[Unit]
    val req0 = {
      val hs = new DefaultHttp2Headers
      hs.scheme("http")
      hs.method("sup")
      hs.path("/")
      hs.authority("auf")
      val data = new Stream.Reader {
        def onEnd = req0EndP
        def read() = req0q.poll()
        def reset(exn: Throwable) = req0q.fail(exn)
      }
      Netty4Message.Request(hs, data)
    }
    log.debug("dispatching req0")
    val rsp0f = dispatcher(req0)
    assert(!rsp0f.isDefined)

    val req1 = {
      val hs = new DefaultHttp2Headers
      hs.scheme("http")
      hs.method("sup")
      hs.path("/")
      hs.authority("auf")
      Netty4Message.Request(hs, Stream.Nil)
    }
    log.debug("dispatching req1")
    val rsp1f = dispatcher(req1)
    assert(!rsp1f.isDefined)

    // Initial headers were sent to the server for req0
    val req0InitF = sentq.poll()
    assert(req0InitF.isDefined)
    await(req0InitF) match {
      case hf: Http2HeadersFrame =>
        assert(hf.headers.method == "sup")
        assert(hf.streamId == 3)
      case f =>
        fail(s"unexpected frame: $f")
    }
    log.debug("headers were sent for req0")

    // Initial headers were sent to the server for req1
    val req1InitF = sentq.poll()
    assert(req1InitF.isDefined)
    await(req1InitF) match {
      case hf: Http2HeadersFrame =>
        assert(hf.headers.method == "sup")
        assert(hf.streamId == 5)
      case f =>
        fail(s"unexpected frame: $f")
    }
    log.debug("headers were sent for req1")

    log.debug("writing a data frame to req0")
    val buf = Buf.Utf8("how's it goin?")
    req0q.offer(Frame.Data(buf, true, () => releaser(buf.length)))

    // We receive a response for req1 first:
    log.debug("offering response headers for req1")
    recvq.offer({
      val hs = new DefaultHttp2Headers
      hs.status("222")
      new DefaultHttp2HeadersFrame(hs, false).setStreamId(5)
    })

    log.debug("waiting to receive response headers for req1")
    eventually { assert(rsp1f.isDefined) }
    val rsp1 = await(rsp1f)
    assert(rsp1.status == Status.Cowabunga)
    val data1 = rsp1.data match {
      case Stream.Nil => fail("empty stream")
      case r: Stream.Reader => r
    }

    log.debug("offering response headers for req0")
    assert(!rsp0f.isDefined)
    recvq.offer({
      val hs = new DefaultHttp2Headers
      hs.status("222")
      new DefaultHttp2HeadersFrame(hs, false).setStreamId(3)
    })
    eventually { assert(rsp0f.isDefined) }
    val rsp0 = await(rsp0f)
    assert(rsp0.status == Status.Cowabunga)
    val data0 = rsp0.data match {
      case Stream.Nil => fail("empty stream")
      case r: Stream.Reader => r
    }

    recvq.offer({
      val buf = Buf.Utf8("sup")
      new DefaultHttp2DataFrame(BufAsByteBuf.Owned(buf), true).setStreamId(3)
    })
    recvq.offer({
      val buf = Buf.Utf8("yo")
      new DefaultHttp2DataFrame(BufAsByteBuf.Owned(buf), true).setStreamId(5)
    })

    val d0f = data0.read()
    assert(d0f.isDefined)
    assert(data0.onEnd.isDefined)

    val d1f = data1.read()
    assert(d1f.isDefined)
    assert(data1.onEnd.isDefined)

    await(d0f) match {
      case f: Frame.Data =>
        assert(f.buf == Buf.Utf8("sup"))
        assert(f.isEnd)
      case f =>
        fail(s"unexpected frame: $f")
    }
    await(d1f) match {
      case f: Frame.Data =>
        assert(f.buf == Buf.Utf8("yo"))
        assert(f.isEnd)
      case f =>
        fail(s"unexpected frame: $f")
    }
  }
}
