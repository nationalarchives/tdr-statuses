# TDR Statuses

This lambda takes file information as an input and produces a list of file and consignment statuses.

## File Statuses

Given this json as input:
```json

{
  "results": [
    {
      "fileId": "20d80488-d247-47cf-8687-be26de2558b5",
      "originalPath": "smallfile/subfolder/subfolder-nested/subfolder-nested-1.txt",
      "fileSize": "2",
      "clientChecksum": "87428fc522803d31065e7bce3cf03fe475096631e5e07bbd7a0fde60c4cf25c7",
      "consignmentType": "standard",
      "fileCheckResults": {
        "antivirus": [
          {
            "result": ""
          }
        ],
        "checksum": [
          {
            "sha256Checksum": "87428fc522803d31065e7bce3cf03fe475096631e5e07bbd7a0fde60c4cf25c7"
          }
        ],
        "fileFormat": [
          {
            "matches": [
              {
                "puid": "fmt/866"
              }
            ]
          }
        ]
      }
    }
  ],
  "redactedResults": {
    "redactedFiles": [
      {
        "originalFileId": "4dbabaef-1fae-4d09-8faa-88e9dfb85b05",
        "originalFilePath": "originalPath",
        "redactedFileId": "7daa4ab6-ab7d-449a-88f5-a8ff1705b888",
        "redactedFilePath": "redactedPath"
      }
    ],
    "errors": [
      {
        "fileId": "97536958-2192-4485-af9a-d98a0290e692",
        "cause": "FailureReason"
      }
    ]
  }
}
```

The lambda processes the following file statuses for each file.

### Antivirus
* `Success` if antivirus result is empty
* `VirusDetected` if the result is not empty

### FFID
* If the consignment type is judgment and the puid is not in the AllowedPuids table then `NonJudgmentFormat`.
* If the consignment type is standard and the puid is in the DisallowedPuids table and is active then get the status from the `Reason` column.
* If the file size is zero then `ZeroByteFile`.
* Otherwise `Success`.

### ChecksumMatch
* If the server checksum doesn't match the client checksum then `Mismatch`.
* If the two checksums match then `Success`.

### ServerChecksum
* If the server checksum is empty then `Failed`.
* If the server checksum is not empty then `Success`.

### ClientChecksum
* If the client checksum is empty then `Failed`.
* If the client checksum is not empty then `Success`.

### ClientFilePath
* If the client file path is empty then `Failed`.
* If the client file path is not empty then `Success`.

### Redaction
* If the redactedFiles array is empty and the errors array is empty then no results.
* If the redactedFiles array is not empty then `Success` for each `redactedFileId`.
* If the errors array is not empty then the status value comes from the `cause` field.

### ServerFFID
* Check each FFID file status. If the statuses are all either `Success` or in the `DisallowedPuids` table but inactive then set to `Completed`.
* If any of the FFID file statuses are set to a status from the `DisallowedPuids` table where `Active` is true then `CompletedWithErrors`

## Adding a new status
* Add a method into `StatusProcessor`. This should return `F[List[Status]]`
* Call this new method in the `statusChecks` method in the `Lambda` class and return the result in the `yield` block.
* Add a test in Lambda test for this status.

## Running locally
There is a `LambdaRunner` class which can be run. You can change the input json as necessary.
