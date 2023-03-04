// SPDX-License-Identifier: GPL-3.0
 
pragma solidity >=0.5.0 <0.9.0;
 
// Declare a function that concatenates two strings.

contract A{
    string public s1 = "aaa";
    string public s2 = "bbb";
    string public new_str;
    
// Since Solidity does not offer a native way to concatenate strings I will use abi.encodePacked() to do that.  (method 1)
    function concatenate1() public view returns(string memory){
        string memory s3;
        s3 = string(abi.encodePacked(s1, s2));
        return s3;
    }
    
// (method 2)
     function concatenate2() public {
        new_str = string(abi.encodePacked(s1, s2));
     }
}
