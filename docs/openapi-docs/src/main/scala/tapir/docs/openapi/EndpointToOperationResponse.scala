package tapir.docs.openapi

import tapir.internal._
import tapir.docs.openapi.schema.ObjectSchemas
import tapir.model.StatusCode
import tapir.openapi.OpenAPI.ReferenceOr
import tapir.openapi._
import tapir.{Schema => SSchema, _}

import scala.collection.immutable.ListMap

private[openapi] class EndpointToOperationResponse(objectSchemas: ObjectSchemas, codecToMediaType: CodecToMediaType) {
  def apply(e: Endpoint[_, _, _, _]): ListMap[ResponsesKey, ReferenceOr[Response]] = {
    // There always needs to be at least a 200 empty response
    outputToResponses(e.output, ResponsesCodeKey(200), Some(Response("", ListMap.empty, ListMap.empty))) ++
      outputToResponses(e.errorOutput, ResponsesDefaultKey, None)
  }

  private def outputToResponses(
      output: EndpointOutput[_],
      defaultResponseKey: ResponsesKey,
      defaultResponse: Option[Response]
  ): ListMap[ResponsesKey, ReferenceOr[Response]] = {

    val responses: ListMap[ResponsesKey, ReferenceOr[Response]] = output.asBasicOutputsMap.flatMap {
      case (sc, outputs) =>
        // there might be no output defined at all
        outputsToResponse(outputs)
          .map { response =>
            // using the "default" response key, if no status code is provided
            val responseKey = sc.map(ResponsesCodeKey).getOrElse(defaultResponseKey)
            responseKey -> Right(response)
          }
    }

    if (responses.isEmpty) {
      // no output at all - using default if defined
      defaultResponse.map(defaultResponseKey -> Right(_)).toIterable.toListMap
    } else responses
  }

  private def outputsToResponse(outputs: Vector[EndpointOutput[_]]): Option[Response] = {
    val headers = outputs.collect {
      case EndpointIO.Header(name, codec, info) =>
        name -> Right(
          Header(
            info.description,
            Some(!codec.meta.isOptional),
            None,
            None,
            None,
            None,
            None,
            Some(objectSchemas(codec.meta.schema)),
            info.example.flatMap(exampleValue(codec, _)),
            ListMap.empty,
            ListMap.empty
          )
        )
    }

    val bodies = outputs.collect {
      case EndpointIO.Body(m, i)                                            => (i.description, codecToMediaType(m, i.example))
      case EndpointIO.StreamBodyWrapper(StreamingEndpointIO.Body(s, mt, i)) => (i.description, codecToMediaType(s, mt, i.example))
    }
    val body = bodies.headOption

    val description = body.flatMap(_._1).getOrElse("")
    val content = body.map(_._2).getOrElse(ListMap.empty)

    if (body.isDefined || headers.nonEmpty) {
      Some(Response(description, headers.toListMap, content))
    } else if (outputs.nonEmpty) {
      Some(Response("", ListMap.empty, ListMap.empty))
    } else {
      None
    }
  }
}
